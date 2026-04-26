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
                            sendResponse(exchange, 400, json("error", "Coupon limit reached for your account"));
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
                                    sendResponse(exchange, 400, json("error", "Valid for first-time users only"));
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
                    sendResponse(exchange, 404, json("error", "Coupon code not found"));
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
        double walletAmt = toDouble(data.get("wallet_amount_deducted"));
        String couponCode = str(data.get("coupon_code"));

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (walletAmt > 0) handleWalletUsage(conn, userId, bookingId, walletAmt);
                if (!couponCode.isEmpty()) handleCouponUsage(conn, userId, couponCode);

                String sql = """
                    INSERT INTO bookings_info (
                        partner_id, hotel_id, booking_id, hotel_name, booking_status, hotel_type, room_type, 
                        user_id, guest_name, email, check_in_date, check_out_date, guest_count, adults, 
                        children, total_rooms_booked, total_days_at_stay, room_price_per_day, room_price_per_month, 
                        months, all_days_price, gst, original_amount, payment_method_type, payment_status, 
                        wallet_used, wallet_amount_deducted, coupon_code, coupon_discount_amount, 
                        final_payable_amount, amount_paid_online, due_amount_at_hotel, paid_via, transaction_id, 
                        last_payment_record_id, hotel_address, hotel_contact
                    ) VALUES (
                        ?,?,?,?,?::booking_status_enum,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::yes_no_enum,?,?,?,?,?,?,?,?,?,?
                    )
                """;
                
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, str(data.get("partner_id")));
                    ps.setString(2, str(data.get("hotel_id")));
                    ps.setString(3, bookingId);
                    ps.setString(4, str(data.get("hotel_name")));
                    ps.setString(5, str(data.getOrDefault("booking_status", "PENDING")));
                    ps.setString(6, str(data.get("hotel_type")));
                    ps.setString(7, str(data.get("room_type")));
                    ps.setString(8, userId);
                    ps.setString(9, str(data.get("guest_name")));
                    ps.setString(10, str(data.get("email")));
                    ps.setDate(11, parseSqlDate(data.get("check_in_date")));
                    ps.setDate(12, parseSqlDate(data.get("check_out_date")));
                    ps.setInt(13, toInt(data.get("guest_count")));
                    ps.setInt(14, toInt(data.get("adults")));
                    ps.setInt(15, toInt(data.get("children")));
                    ps.setInt(16, toInt(data.get("total_rooms_booked")));
                    ps.setInt(17, toInt(data.get("total_days_at_stay")));
                    ps.setDouble(18, toDouble(data.get("room_price_per_day")));
                    ps.setString(19, str(data.get("room_price_per_month")));
                    ps.setInt(20, toInt(data.get("months")));
                    ps.setDouble(21, toDouble(data.get("all_days_price")));
                    ps.setDouble(22, toDouble(data.get("gst")));
                    ps.setDouble(23, toDouble(data.get("total_price"))); // original_amount
                    ps.setString(24, str(data.get("payment_method_type")));
                    ps.setString(25, str(data.get("payment_status")));
                    ps.setString(26, str(data.get("wallet_used")));
                    ps.setDouble(27, walletAmt);
                    ps.setString(28, couponCode);
                    ps.setDouble(29, toDouble(data.get("coupon_discount_amount")));
                    ps.setDouble(30, toDouble(data.get("final_payable_amount")));
                    ps.setDouble(31, toDouble(data.get("amount_paid_online")));
                    ps.setDouble(32, toDouble(data.get("due_amount_at_hotel")));
                    ps.setString(33, str(data.get("paid_via")));
                    ps.setString(34, str(data.get("transaction_id")));
                    ps.setString(35, str(data.get("last_payment_record_id")));
                    ps.setString(36, str(data.get("hotel_address")));
                    ps.setString(37, str(data.get("hotel_contact")));
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
            ps.setString(8, "Booking " + bId);
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
            psTxn.setString(4, "Payment failure refund for " + bId);
            psTxn.setString(5, userId);
            psTxn.executeUpdate();

            conn.commit();
            sendResponse(exchange, 200, json("message", "Refund processed"));
        } catch (Exception e) {
            sendResponse(exchange, 500, json("error", e.getMessage()));
        }
    }

    private String generateBookingId() { return "BKG" + (100000 + new Random().nextInt(900000)); }
    private double toDouble(Object o) { if (o == null) return 0; try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0; } }
    private String str(Object o) { return o == null ? "" : o.toString().trim(); }
    private int toInt(Object o) { if (o == null) return 0; try { return Integer.parseInt(o.toString()); } catch (Exception e) { return 0; } }
    
    private java.sql.Date parseSqlDate(Object val) {
        if (val == null || val.toString().isEmpty()) return null;
        try {
            String s = val.toString().trim();
            if (s.contains("-")) {
                String[] p = s.split("-");
                if (p[0].length() == 4) return java.sql.Date.valueOf(s);
                return java.sql.Date.valueOf(p[2] + "-" + p[1] + "-" + p[0]);
            }
        } catch (Exception ignored) {}
        return null;
    }

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
    private String json(String k1, String v1, String k2, String v2) { return "{\"" + k1 + "\":\"" + v1 + "\",\"" + k2 + "\":\"" + v2 + "\"}"; }
}