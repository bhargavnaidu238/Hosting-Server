package com.hotel.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.utilities.DbConfig;
import com.razorpay.*;
import com.sun.net.httpserver.*;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class PaymentHandler implements HttpHandler {

    private final DbConfig dbConfig;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String RZP_KEY; 
    private final String RZP_SECRET;
    private final String WEBHOOK_SECRET; 

    public PaymentHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
        this.RZP_KEY = dbConfig.getApiKey();
        this.RZP_SECRET = dbConfig.getAPIKeySecret();
        this.WEBHOOK_SECRET = dbConfig.getAPIKeySecret();
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return;
        }

        String path = ex.getRequestURI().getPath();
        try {
            switch (path) {
                case "/payment/createOrder" -> createOrder(ex);
                case "/payment/verify" -> verifyFromClient(ex);
                case "/payment/webhook" -> handleWebhook(ex); 
                default -> respond(ex, 404, json("error", "Endpoint not found"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            respond(ex, 500, json("error", e.getMessage()));
        }
    }

    private void createOrder(HttpExchange ex) throws IOException {
        Map<String, Object> req = readJson(ex);
        try {
             RazorpayClient client = new RazorpayClient(RZP_KEY, RZP_SECRET);
            JSONObject orderReq = new JSONObject();
            
            double amountInRupees = toDouble(req.get("amount"));
            orderReq.put("amount", (int)(amountInRupees)); // Amount is already in paise from Flutter
            orderReq.put("currency", "INR");
            orderReq.put("payment_capture", 1);

            Order order = client.orders.create(orderReq);
            
            // Create a temporary reference ID for the frontend to track this attempt
            String tempRecordId = "PRID_" + UUID.randomUUID().toString().substring(0, 8);

            JSONObject res = new JSONObject();
            res.put("order_id", order.get("id").toString());
            res.put("razorpay_key_id", RZP_KEY);
            res.put("amount", order.get("amount").toString());
            res.put("payment_record_id", tempRecordId); 
            
            respond(ex, 200, res.toString());
        } catch (Exception e) {
            respond(ex, 500, json("error", e.getMessage()));
        }
    }

    private void verifyFromClient(HttpExchange ex) throws IOException {
        Map<String, Object> p = readJson(ex);

        String bookingId = str(p.get("booking_id"));
        String userId = str(p.get("user_id"));
        String partnerId = str(p.get("partner_id"));
        String hotelId = str(p.get("hotel_id"));
        String orderId = str(p.get("gateway_order_id"));
        String paymentId = str(p.get("gateway_payment_id"));
        String signature = str(p.get("gateway_signature"));
        String prid = str(p.get("payment_record_id")); 
        double amount = toDouble(p.get("final_payable_amount"));

        if(prid.isEmpty()) prid = UUID.randomUUID().toString();

        processPaymentUpdate(ex, prid, bookingId, userId, partnerId, hotelId, orderId, paymentId, signature, amount, false);
    }

    private void handleWebhook(HttpExchange ex) throws IOException {
        String signature = ex.getRequestHeaders().getFirst("X-Razorpay-Signature");
        String body = new BufferedReader(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8))
                .lines().collect(java.util.stream.Collectors.joining("\n"));

        try {
            if (Utils.verifyWebhookSignature(body, signature, WEBHOOK_SECRET)) {
                JSONObject json = new JSONObject(body);
                String event = json.getString("event");
                
                if ("payment.captured".equals(event)) {
                    JSONObject payment = json.getJSONObject("payload").getJSONObject("payment").getJSONObject("entity");
                    String orderId = payment.getString("order_id");
                    String paymentId = payment.getString("id");
                    double amount = payment.getDouble("amount") / 100.0;

                    Map<String, String> bookingMap = lookupBookingByOrderId(orderId);
                    if (!bookingMap.isEmpty()) {
                        processPaymentUpdate(ex, UUID.randomUUID().toString(), 
                            bookingMap.get("booking_id"), bookingMap.get("user_id"), 
                            bookingMap.get("partner_id"), bookingMap.get("hotel_id"), 
                            orderId, paymentId, "WEBHOOK", amount, true);
                    }
                }
                respond(ex, 200, "Webhook Processed");
            } else {
                respond(ex, 401, "Invalid Webhook Signature");
            }
        } catch (Exception e) {
            respond(ex, 500, "Webhook Error");
        }
    }

    private void processPaymentUpdate(HttpExchange ex, String prid, String bid, String uid, String pid, String hid, 
                                     String oid, String payid, String sig, double amt, boolean isWebhook) throws IOException {
        try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {
            conn.setAutoCommit(false);
            
            String status = "FAILED"; 
            String failureReason = "";
            
            if (!isWebhook && !sig.equals("WEBHOOK")) {
                try {
                    JSONObject attr = new JSONObject();
                    attr.put("razorpay_order_id", oid);
                    attr.put("razorpay_payment_id", payid);
                    attr.put("razorpay_signature", sig);
                    Utils.verifyPaymentSignature(attr, RZP_SECRET);
                    status = "PAID"; 
                } catch (RazorpayException e) {
                    failureReason = e.getMessage();
                    status = "FAILED";
                }
            } else {
                status = "PAID"; 
            }

            int attemptNo = nextAttempt(conn, bid);
            
            insertPaymentRecord(conn, prid, bid, uid, pid, hid, oid, payid, sig, status, failureReason, amt, attemptNo);
            updateBookingStatus(conn, bid, status, payid, prid);

            conn.commit();
            
            // This is where the error was happening - calling the 4-argument json method
            if(!isWebhook) respond(ex, 200, json("status", status, "record_id", prid));
            
        } catch (Exception e) {
            e.printStackTrace(); 
            if(!isWebhook) respond(ex, 500, json("error", e.getMessage()));
        }
    }

    private void insertPaymentRecord(Connection conn, String prid, String bid, String uid, String pid, String hid, 
                                     String oid, String payid, String sig, String status, String failure, 
                                     double amt, int attempt) throws SQLException {
        String sql = "INSERT INTO payment_transactions (payment_record_id, booking_id, user_id, partner_id, hotel_id, " +
                     "payment_gateway, gateway_order_id, gateway_payment_id, gateway_signature, payment_method, " +
                     "payment_status, failure_reason, amount, currency, payment_attempt_no, is_refunded, " +
                     "refund_amount, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::yes_no_enum,?, NOW(), NOW())";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, prid);
            ps.setString(2, bid);
            ps.setString(3, uid);
            ps.setString(4, pid);
            ps.setString(5, hid);
            ps.setString(6, "Razorpay");
            ps.setString(7, oid);
            ps.setString(8, payid);
            ps.setString(9, sig);
            ps.setString(10, "ONLINE");
            ps.setString(11, status);
            ps.setString(12, failure);
            ps.setDouble(13, amt);
            ps.setString(14, "INR");
            ps.setInt(15, attempt);
            ps.setString(16, "No"); 
            ps.setDouble(17, 0.00);
            ps.executeUpdate();
        }
    }

    private void updateBookingStatus(Connection conn, String bid, String paymentStatus, String payId, String prid) throws SQLException {
        String finalBookingStatus = paymentStatus.equalsIgnoreCase("PAID") ? "CONFIRMED" : "PENDING";
        
        String sql = "UPDATE bookings_info SET payment_status = ?, transaction_id = ?, " +
                     "last_payment_record_id = ?, payment_confirmed_at = NOW(), booking_status = ?::booking_status_enum WHERE booking_id = ?";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, paymentStatus); 
            ps.setString(2, payId);
            ps.setString(3, prid);
            ps.setString(4, finalBookingStatus); 
            ps.setString(5, bid);
            ps.executeUpdate();
        }
    }

    private Map<String, String> lookupBookingByOrderId(String orderId) {
        Map<String, String> map = new HashMap<>();
        String sql = "SELECT booking_id, user_id, partner_id, hotel_id FROM bookings_info WHERE transaction_id = ? OR last_payment_record_id IN (SELECT payment_record_id FROM payment_transactions WHERE gateway_order_id = ?)";
        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, orderId);
            ps.setString(2, orderId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                map.put("booking_id", rs.getString("booking_id"));
                map.put("user_id", rs.getString("user_id"));
                map.put("partner_id", rs.getString("partner_id"));
                map.put("hotel_id", rs.getString("hotel_id"));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return map;
    }

    private int nextAttempt(Connection conn, String bid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM payment_transactions WHERE booking_id = ?")) {
            ps.setString(1, bid);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) + 1 : 1;
        }
    }

    private void addCors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
    }

    private void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        OutputStream os = ex.getResponseBody();
        os.write(b);
        os.close();
    }

    private Map<String, Object> readJson(HttpExchange ex) throws IOException {
        return mapper.readValue(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8), Map.class);
    }

    private String str(Object o) { return o == null ? "" : o.toString(); }
    
    private double toDouble(Object o) {
        if (o == null) return 0.0;
        try { return Double.parseDouble(o.toString().replace(",", "")); } catch (Exception e) { return 0.0; }
    }

    // Fixed: Standard JSON helper
    private String json(String k, String v) { 
        return "{\"" + k + "\":\"" + v + "\"}"; 
    }

    // Added: Overloaded JSON helper for 4 arguments (2 key-value pairs)
    private String json(String k1, String v1, String k2, String v2) {
        return "{\"" + k1 + "\":\"" + v1 + "\",\"" + k2 + "\":\"" + v2 + "\"}";
    }
}