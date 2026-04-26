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

    // Razorpay credentials
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
            
            int amountInPaise = toInt(req.get("amount")); 
            
            JSONObject orderReq = new JSONObject();
            orderReq.put("amount", amountInPaise); 
            orderReq.put("currency", "INR");
            orderReq.put("payment_capture", 1);
            
            // This ID will be used to track the attempt in the Flutter frontend
            String tempPrid = UUID.randomUUID().toString();

            Order order = client.orders.create(orderReq);
            
            JSONObject res = new JSONObject();
            res.put("order_id", order.get("id").toString());
            res.put("razorpay_key_id", RZP_KEY);
            res.put("amount", order.get("amount").toString());
            res.put("payment_record_id", tempPrid); 
            
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
        String existingPrid = str(p.get("payment_record_id"));
        double amount = toDouble(p.get("final_payable_amount"));

        processPaymentUpdate(ex, bookingId, userId, partnerId, hotelId, orderId, paymentId, signature, amount, existingPrid, false);
    }

    private void handleWebhook(HttpExchange ex) throws IOException {
        String signature = ex.getRequestHeaders().getFirst("X-Razorpay-Signature");
        String body = new BufferedReader(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8))
                .lines().collect(java.util.stream.Collectors.joining("\n"));

        try {
            if (Utils.verifyWebhookSignature(body, signature, WEBHOOK_SECRET)) {
                respond(ex, 200, "Webhook Processed");
            } else {
                respond(ex, 401, "Invalid Webhook Signature");
            }
        } catch (Exception e) {
            respond(ex, 500, "Webhook Error");
        }
    }

    private void processPaymentUpdate(HttpExchange ex, String bid, String uid, String pid, String hid, 
                                     String oid, String payid, String sig, double amt, String prid, boolean isWebhook) throws IOException {
        try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {
            
            // 1. IDEMPOTENCY CHECK
            // If this payment ID already exists, the transaction is already complete.
            if (payid != null && !payid.isEmpty()) {
                try (PreparedStatement check = conn.prepareStatement("SELECT 1 FROM payment_transactions WHERE gateway_payment_id = ?")) {
                    check.setString(1, payid);
                    try (ResultSet rs = check.executeQuery()) {
                        if (rs.next()) {
                            if (!isWebhook) respond(ex, 200, json("status", "PAID", "message", "Already Processed"));
                            return;
                        }
                    }
                }
            }

            // 2. SIGNATURE VERIFICATION
            String status = "FAILED";
            String failureReason = "";
            try {
                JSONObject attr = new JSONObject();
                attr.put("razorpay_order_id", oid);
                attr.put("razorpay_payment_id", payid);
                attr.put("razorpay_signature", sig);
                Utils.verifyPaymentSignature(attr, RZP_SECRET);
                status = "PAID";
            } catch (RazorpayException e) {
                status = "FAILED";
                failureReason = e.getMessage();
            }

            // 3. DATABASE UPDATE BLOCK
            conn.setAutoCommit(false);
            try {
                // Use the ID provided by the frontend for consistency
                String finalPrid = (prid == null || prid.trim().isEmpty()) ? UUID.randomUUID().toString() : prid;
                int attemptNo = nextAttempt(conn, bid);
                
                insertPaymentRecord(conn, finalPrid, bid, uid, pid, hid, oid, payid, sig, status, failureReason, amt, attemptNo);
                updateBookingStatus(conn, bid, status, payid, finalPrid);

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
            
            // 4. FINAL RESPONSE
            if(!isWebhook) respond(ex, 200, json("status", status, "record_id", prid));
            
        } catch (Exception e) {
            e.printStackTrace();
            if(!isWebhook) respond(ex, 500, json("error", "Payment verification internal failure"));
        }
    }

    private void insertPaymentRecord(Connection conn, String prid, String bid, String uid, String pid, String hid, 
                                     String oid, String payid, String sig, String status, String failure, 
                                     double amt, int attempt) throws SQLException {
        String sql = "INSERT INTO payment_transactions (" +
                     "payment_record_id, booking_id, user_id, partner_id, hotel_id, " +
                     "payment_gateway, gateway_order_id, gateway_payment_id, gateway_signature, " +
                     "payment_method, payment_status, failure_reason, amount, currency, " +
                     "payment_attempt_no, is_refunded, refund_amount, created_at, updated_at" +
                     ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, NOW(), NOW())";

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
            ps.setString(10, "Online");
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

    private void updateBookingStatus(Connection conn, String bid, String status, String payId, String prid) throws SQLException {
        String sql = "UPDATE bookings_info SET payment_status = ?, transaction_id = ?, " + 
                     "last_payment_record_id = ?, booking_status = ?::booking_status_enum, " +
                     "payment_confirmed_at = NOW() WHERE booking_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status); 
            ps.setString(2, payId);
            ps.setString(3, prid);
            ps.setString(4, status.equalsIgnoreCase("PAID") ? "CONFIRMED" : "PENDING");
            ps.setString(5, bid);
            ps.executeUpdate();
        }
    }

    private int nextAttempt(Connection conn, String bid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM payment_transactions WHERE booking_id = ?")) {
            ps.setString(1, bid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) + 1 : 1;
            }
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
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        ex.close();
    }

    private Map<String, Object> readJson(HttpExchange ex) throws IOException {
        return mapper.readValue(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8), Map.class);
    }

    private String str(Object o) { return o == null ? "" : o.toString(); }
    
    private double toDouble(Object o) {
        if (o == null) return 0.0;
        try { return Double.parseDouble(o.toString().replace(",", "")); } catch (Exception e) { return 0.0; }
    }

    private int toInt(Object o) {
        if (o == null) return 0;
        try { return (int) Double.parseDouble(o.toString()); } catch (Exception e) { return 0; }
    }

    private String json(String k, String v) { 
        return "{\"" + k + "\":\"" + v + "\"}"; 
    }

    private String json(String k1, String v1, String k2, String v2) {
        return "{\"" + k1 + "\":\"" + v1 + "\",\"" + k2 + "\":\"" + v2 + "\"}";
    }
}