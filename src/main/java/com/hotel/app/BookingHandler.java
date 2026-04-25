package com.hotel.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

public class BookingHandler implements HttpHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DbConfig dbConfig;

    public BookingHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        try {
            switch (path) {
                case "/booking" -> handleBooking(exchange);
                case "/getWalletBalance" -> handleGetWalletBalance(exchange);
                case "/validateCoupon" -> handleValidateCoupon(exchange);
                case "/rollbackWallet" -> handleRollbackWallet(exchange);
                default -> sendResponse(exchange, 404, json("error", "Endpoint not found"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, json("error", e.getMessage()));
        }
    }

    private void handleGetWalletBalance(HttpExchange exchange) throws IOException {
        Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
        String userId = params.get("user_id");
        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT balance FROM wallets WHERE user_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                double balance = rs.next() ? rs.getDouble("balance") : 0.0;
                sendResponse(exchange, 200, "{\"balance\":" + balance + "}");
            }
        } catch (SQLException e) {
            sendResponse(exchange, 500, json("error", e.getMessage()));
        }
    }

    private void handleValidateCoupon(HttpExchange exchange) throws IOException {
        Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
        String code = params.get("code");
        String userId = params.get("user_id");

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("SELECT * FROM coupons WHERE UPPER(coupon_code) = UPPER(?) AND status = 'active'");
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String couponId = rs.getString("coupon_id");
                    int limitPerUser = rs.getInt("usage_limit_per_user");

                    PreparedStatement psUsage = conn.prepareStatement("SELECT usage_count FROM coupon_usage WHERE coupon_id = ? AND user_id = ?");
                    psUsage.setString(1, couponId);
                    psUsage.setString(2, userId);
                    try (ResultSet rsUsage = psUsage.executeQuery()) {
                        if (rsUsage.next() && rsUsage.getInt("usage_count") >= limitPerUser) {
                            sendResponse(exchange, 400, json("error", "Usage limit exceeded for this coupon"));
                            return;
                        }
                    }
                    
                    PreparedStatement psRule = conn.prepareStatement("SELECT rule_value FROM coupon_rules WHERE coupon_id = ? AND rule_type = 'first_booking_only'");
                    psRule.setString(1, couponId);
                    try (ResultSet rsRule = psRule.executeQuery()) {
                        if (rsRule.next() && "true".equalsIgnoreCase(rsRule.getString("rule_value"))) {
                            PreparedStatement psCheck = conn.prepareStatement("SELECT COUNT(*) FROM bookings_info WHERE user_id = ? AND booking_status = 'CONFIRMED'");
                            psCheck.setString(1, userId);
                            try (ResultSet rsCheck = psCheck.executeQuery()) {
                                if (rsCheck.next() && rsCheck.getInt(1) > 0) {
                                    sendResponse(exchange, 400, json("error", "Valid for first booking only"));
                                    return;
                                }
                            }
                        }
                    }

                    Map<String, Object> coupon = new HashMap<>();
                    coupon.put("coupon_id", couponId);
                    coupon.put("discount_type", rs.getString("discount_type"));
                    coupon.put("discount_value", rs.getDouble("discount_value"));
                    coupon.put("max_discount", rs.getDouble("max_discount"));
                    coupon.put("min_order_value", rs.getDouble("min_order_value"));
                    sendResponse(exchange, 200, objectMapper.writeValueAsString(coupon));
                } else {
                    sendResponse(exchange, 404, json("error", "Invalid or inactive coupon"));
                }
            }
        } catch (SQLException e) {
            sendResponse(exchange, 500, json("error", e.getMessage()));
        }
    }

    private void handleBooking(HttpExchange exchange) throws IOException {
        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody())).lines().collect(Collectors.joining("\n"));
        Map<String, Object> data = objectMapper.readValue(body, Map.class);

        String bookingId = generateBookingId();
        String userId = str(data.get("user_id"));
        double walletRequested = toDouble(data.get("wallet_amount_deducted"));
        String couponCode = str(data.get("coupon_code"));

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (walletRequested > 0) {
                    handleWalletUsage(conn, userId, bookingId, walletRequested);
                }

                if (!couponCode.isEmpty()) {
                    handleCouponUsage(conn, userId, couponCode);
                }

                // UPDATED SQL: Aligned column names with your database schema
                String sql = "INSERT INTO bookings_info (partner_id, hotel_id, booking_id, hotel_name, guest_name, email, user_id, " +
                             "original_amount, final_payable_amount, wallet_used, wallet_amount_deducted, coupon_code, coupon_discount_amount, " +
                             "payment_status, booking_status, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, NOW())";
                
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, str(data.get("partner_id")));
                    ps.setString(2, str(data.get("hotel_id")));
                    ps.setString(3, bookingId);
                    ps.setString(4, str(data.get("hotel_name")));
                    ps.setString(5, str(data.get("guest_name")));
                    ps.setString(6, str(data.get("email")));
                    ps.setString(7, userId);
                    ps.setDouble(8, toDouble(data.get("total_price"))); // Maps to original_amount
                    ps.setDouble(9, toDouble(data.get("final_payable_amount")));
                    ps.setString(10, str(data.get("wallet_used")));
                    ps.setDouble(11, walletRequested);
                    ps.setString(12, couponCode);
                    ps.setDouble(13, toDouble(data.get("coupon_discount_amount")));
                    ps.setString(14, str(data.get("payment_status")));
                    ps.setString(15, str(data.get("booking_status")));
                    ps.executeUpdate();
                }

                conn.commit();
                sendResponse(exchange, 200, json("message", "Success", "booking_id", bookingId));
            } catch (Exception e) {
                conn.rollback();
                sendResponse(exchange, 500, json("error", e.getMessage()));
            }
        } catch (Exception e) {
            sendResponse(exchange, 500, json("error", e.getMessage()));
        }
    }

    private void handleWalletUsage(Connection conn, String userId, String bId, double req) throws SQLException {
        double balance = 0;
        String wId = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT wallet_id, balance FROM wallets WHERE user_id=? FOR UPDATE")) {
            ps.setString(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                wId = rs.getString("wallet_id");
                balance = rs.getDouble("balance");
            }
        }
        if (wId == null || balance < req) throw new SQLException("Insufficient wallet balance");

        try (PreparedStatement ps = conn.prepareStatement("UPDATE wallets SET balance = balance - ? WHERE wallet_id = ?")) {
            ps.setDouble(1, req);
            ps.setString(2, wId);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO wallet_transactions (txn_id, wallet_id, type, amount, direction, reference_id, status, description, balance_after_txn, created_at) VALUES (?,?,?,?,?,?,?,?,?, NOW())")) {
            ps.setString(1, "WLT" + System.currentTimeMillis());
            ps.setString(2, wId);
            ps.setString(3, "BOOKING_PAYMENT");
            ps.setDouble(4, req);
            ps.setString(5, "DEBIT");
            ps.setString(6, bId);
            ps.setString(7, "SUCCESS");
            ps.setString(8, "Stay Booking " + bId);
            ps.setDouble(9, balance - req);
            ps.executeUpdate();
        }
    }

    private void handleCouponUsage(Connection conn, String uId, String code) throws SQLException {
        String cId = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT coupon_id FROM coupons WHERE UPPER(coupon_code)=UPPER(?)")) {
            ps.setString(1, code);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) cId = rs.getString("coupon_id");
        }
        if (cId == null) return;

        PreparedStatement checkPs = conn.prepareStatement("SELECT usage_id FROM coupon_usage WHERE coupon_id = ? AND user_id = ?");
        checkPs.setString(1, cId);
        checkPs.setString(2, uId);
        ResultSet rsCheck = checkPs.executeQuery();

        if (rsCheck.next()) {
            PreparedStatement updatePs = conn.prepareStatement("UPDATE coupon_usage SET usage_count = usage_count + 1, last_used_at = NOW() WHERE coupon_id = ? AND user_id = ?");
            updatePs.setString(1, cId);
            updatePs.setString(2, uId);
            updatePs.executeUpdate();
        } else {
            PreparedStatement insertPs = conn.prepareStatement("INSERT INTO coupon_usage (usage_id, coupon_id, user_id, usage_count, last_used_at) VALUES (?,?,?,1, NOW())");
            insertPs.setString(1, UUID.randomUUID().toString());
            insertPs.setString(2, cId);
            insertPs.setString(3, uId);
            insertPs.executeUpdate();
        }
    }

    private void handleRollbackWallet(HttpExchange exchange) throws IOException {
        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody())).lines().collect(Collectors.joining("\n"));
        Map<String, Object> data = objectMapper.readValue(body, Map.class);
        String userId = str(data.get("user_id"));
        double amount = toDouble(data.get("amount"));
        String bId = str(data.get("booking_id"));

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {
            conn.setAutoCommit(false);
            PreparedStatement psUpd = conn.prepareStatement("UPDATE wallets SET balance = balance + ? WHERE user_id = ?");
            psUpd.setDouble(1, amount);
            psUpd.setString(2, userId);
            psUpd.executeUpdate();

            PreparedStatement psTxn = conn.prepareStatement("INSERT INTO wallet_transactions (txn_id, wallet_id, type, amount, direction, reference_id, status, description, created_at) " +
                    "SELECT ?, wallet_id, 'REFUND', ?, 'CREDIT', ?, 'SUCCESS', ?, NOW() FROM wallets WHERE user_id = ?");
            psTxn.setString(1, "REF" + System.currentTimeMillis());
            psTxn.setDouble(2, amount);
            psTxn.setString(3, bId);
            psTxn.setString(4, "Gateway Failure Refund for " + bId);
            psTxn.setString(5, userId);
            psTxn.executeUpdate();

            conn.commit();
            sendResponse(exchange, 200, json("message", "Rollback Successful"));
        } catch (Exception e) {
            sendResponse(exchange, 500, json("error", e.getMessage()));
        }
    }

    private String generateBookingId() { return "BKG" + (100000 + new Random().nextInt(900000)); }
    private double toDouble(Object o) { if (o == null) return 0; try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0; } }
    private String str(Object o) { return o == null ? "" : o.toString().trim(); }
    private Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;
        for (String param : query.split("&")) {
            String[] entry = param.split("=");
            if (entry.length > 1) result.put(entry[0], entry[1]);
        }
        return result;
    }
    private void sendResponse(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
    private void addCorsHeaders(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
    }
    private String json(String k, String v) { return "{\"" + k + "\":\"" + v + "\"}"; }
    private String json(String k, String v, String k2, String v2) { return "{\"" + k + "\":\"" + v + "\",\"" + k2 + "\":\"" + v2 + "\"}"; }
}