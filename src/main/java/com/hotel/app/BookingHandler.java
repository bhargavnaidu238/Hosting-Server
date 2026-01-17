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
            if (path.equals("/booking") && exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                handleBooking(exchange);
            } else if (path.equals("/updatePayment") && exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                handleUpdatePayment(exchange);
            } else {
                sendResponse(exchange, 404, json("error", "Invalid endpoint: " + path));
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, json("error", e.getMessage()));
        }
    }

    private void handleBooking(HttpExchange exchange) throws IOException {
        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody()))
                .lines().collect(Collectors.joining("\n"));

        Map<String, Object> data;
        try {
            data = objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            sendResponse(exchange, 400, json("error", "Invalid JSON payload"));
            return;
        }

        boolean isPgMode = data.containsKey("selected_room_type") || data.containsKey("monthly_price");
        String bookingId = generateBookingId();
        String userId = str(data.get("user_id"));

        // Price Normalization
        double originalAmount = toDouble(data.getOrDefault("total_price", data.get("original_total_price")));
        double finalAmount = toDouble(data.get("final_payable_amount"));
        double amountPaidOnline = toDouble(data.get("amount_paid_online"));
        double dueAtHotel = toDouble(data.get("due_amount_at_hotel"));

        // Status Normalization (MUST be Uppercase for PostgreSQL Enum)
        String paymentStatus = normalizePaymentStatus(str(data.get("payment_status")));
        String bookingStatus = str(data.getOrDefault("booking_status", "PENDING")).toUpperCase();

        // Wallet + Coupon Handling
        double walletRequested = toDouble(data.get("wallet_amount_deducted"));
        boolean walletUsedFlag = false;
        Object wUsed = data.get("wallet_used");
        if (wUsed instanceof Boolean) walletUsedFlag = (Boolean) wUsed;
        else if (wUsed instanceof String) walletUsedFlag = "Yes".equalsIgnoreCase((String) wUsed) || "true".equalsIgnoreCase((String) wUsed);

        String couponCode = str(data.get("coupon_code"));
        double couponDiscount = toDouble(data.get("coupon_discount_amount"));

        double actualWalletDebited = 0;
        Connection conn = null;

        try {
            conn = dbConfig.getCustomerDataSource().getConnection();
            conn.setAutoCommit(false);

            // Logic: Apply Wallet/Coupon only if not paying at hotel
            if (!"Offline".equalsIgnoreCase(str(data.get("payment_method_type")))) {
                if (walletUsedFlag && walletRequested > 0 && !userId.isBlank()) {
                    actualWalletDebited = handleWalletUsage(conn, userId, bookingId, walletRequested, originalAmount);
                }
                if (!couponCode.isEmpty()) {
                    handleCouponUsage(conn, userId, couponCode);
                }
            }

            String sql = """
                INSERT INTO bookings_info (
                    partner_id, hotel_id, booking_id, hotel_name, hotel_type, guest_name, email, user_id,
                    check_in_date, check_out_date, guest_count, adults, children, total_rooms_booked,
                    total_days_at_stay, room_price_per_day, all_days_price, gst,
                    original_amount, final_payable_amount, amount_paid_online, due_amount_at_hotel,
                    payment_method_type, paid_via, payment_status, transaction_id,
                    wallet_used, wallet_amount_deducted, coupon_code, coupon_discount_amount,
                    room_type, room_price_per_month, months, hotel_address, hotel_contact, 
                    booking_status, last_payment_record_id
                )
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,
                        ?::yes_no_enum, ?, ?, ?, ?, ?, ?, ?, ?, ?::booking_status_enum, ?)
                """;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, str(data.get("partner_id")));
                ps.setString(2, str(data.get("hotel_id")));
                ps.setString(3, bookingId);
                ps.setString(4, str(data.get("hotel_name")));
                ps.setString(5, isPgMode ? "PG" : str(data.get("hotel_type")));
                ps.setString(6, str(data.get("guest_name")));
                ps.setString(7, str(data.get("email")));
                ps.setString(8, userId);
                ps.setDate(9, parseSqlDate(data.get("check_in_date")));
                ps.setDate(10, parseSqlDate(data.get("check_out_date")));

                // Room Details Logic
                ps.setInt(11, toInt(data.getOrDefault("guest_count", data.get("Persons"))));
                ps.setInt(12, toInt(data.getOrDefault("adults", data.get("Persons"))));
                ps.setInt(13, toInt(data.getOrDefault("children", 0)));
                ps.setInt(14, toInt(data.getOrDefault("total_rooms_booked", 1)));
                ps.setInt(15, toInt(data.getOrDefault("total_days_at_stay", data.get("months"))));
                ps.setDouble(16, toDouble(data.getOrDefault("room_price_per_day", 0)));
                ps.setDouble(17, toDouble(data.getOrDefault("all_days_price", data.get("All_months_Price"))));
                ps.setDouble(18, toDouble(data.get("gst")));
                ps.setDouble(19, originalAmount);
                ps.setDouble(20, finalAmount);
                ps.setDouble(21, amountPaidOnline);
                ps.setDouble(22, dueAtHotel);
                ps.setString(23, str(data.get("payment_method_type")));
                ps.setString(24, str(data.get("paid_via")));
                ps.setString(25, paymentStatus); // PAID or PENDING
                ps.setString(26, str(data.get("transaction_id")));
                ps.setString(27, actualWalletDebited > 0 ? "Yes" : "No");
                ps.setDouble(28, actualWalletDebited);
                ps.setString(29, couponCode);
                ps.setDouble(30, couponDiscount);
                ps.setString(31, str(data.getOrDefault("room_type", data.get("selected_room_type"))));
                ps.setString(32, str(data.getOrDefault("room_price_per_month", data.get("Selected_Room_Price"))));
                ps.setInt(33, toInt(data.getOrDefault("months", 1)));
                ps.setString(34, str(data.get("hotel_address")));
                ps.setString(35, str(data.get("hotel_contact")));
                ps.setString(36, bookingStatus); // CONFIRMED or PENDING
                ps.setString(37, str(data.get("last_payment_record_id")));

                ps.executeUpdate();
            }

            conn.commit();
            sendResponse(exchange, 200, json("message", "Success", "booking_id", bookingId));

        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ignored) {}
            e.printStackTrace();
            sendResponse(exchange, 500, json("error", e.getMessage()));
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException ignored) {}
        }
    }

    private double handleWalletUsage(Connection conn, String userId, String bId, double req, double total) throws SQLException {
        double maxAllowed = total * 0.5;
        double finalReq = Math.min(req, maxAllowed);
        double balance = 0;
        String wId = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT wallet_id, balance FROM wallets WHERE user_id=?")) {
            ps.setString(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                wId = rs.getString("wallet_id");
                balance = rs.getDouble("balance");
            }
        }
        if (wId == null || balance < finalReq) return 0; // Guard against insufficient balance
        
        try (PreparedStatement ps = conn.prepareStatement("UPDATE wallets SET balance = balance - ? WHERE wallet_id = ?")) {
            ps.setDouble(1, finalReq);
            ps.setString(2, wId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO wallet_transactions (txn_id, wallet_id, type, amount, direction, reference_id, status, description, balance_after_txn) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, "WLT" + System.currentTimeMillis());
            ps.setString(2, wId);
            ps.setString(3, "BOOKING_PAYMENT");
            ps.setDouble(4, finalReq);
            ps.setString(5, "DEBIT");
            ps.setString(6, bId);
            ps.setString(7, "SUCCESS");
            ps.setString(8, "Booking " + bId);
            ps.setDouble(9, balance - finalReq);
            ps.executeUpdate();
        }
        return finalReq;
    }

    private void handleCouponUsage(Connection conn, String uId, String code) throws SQLException {
        String cId = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT coupon_id FROM coupons WHERE coupon_code=?")) {
            ps.setString(1, code);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) cId = rs.getString("coupon_id");
        }
        if (cId == null) return;
        
        // POSTGRESQL UPSERT SYNTAX
        String sql = """
            INSERT INTO coupon_usage (usage_id, coupon_id, user_id, usage_count, last_used_at) 
            VALUES (?,?,?,1, NOW()) 
            ON CONFLICT (coupon_id, user_id) 
            DO UPDATE SET usage_count = coupon_usage.usage_count + 1, last_used_at = EXCLUDED.last_used_at
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, cId);
            ps.setString(3, uId);
            ps.executeUpdate();
        }
    }

    private void handleUpdatePayment(HttpExchange exchange) throws IOException {
        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody())).lines().collect(Collectors.joining("\n"));
        Map<String, Object> payload = objectMapper.readValue(body, Map.class);
        String bId = str(payload.get("booking_id"));
        String status = normalizePaymentStatus(str(payload.get("payment_status")));
        
        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE bookings_info SET payment_status=?, booking_status='CONFIRMED' WHERE booking_id=?")) {
            ps.setString(1, status);
            ps.setString(2, bId);
            ps.executeUpdate();
            sendResponse(exchange, 200, json("message", "Updated"));
        } catch (SQLException e) {
            sendResponse(exchange, 500, json("error", e.getMessage()));
        }
    }

    // Helper methods
    private String generateBookingId() { return "BKG" + (100000 + new Random().nextInt(900000)); }
    private double toDouble(Object o) { if (o == null) return 0; try { return Double.parseDouble(o.toString().replace(",", "")); } catch (Exception e) { return 0; } }
    private int toInt(Object o) { if (o == null) return 0; try { return Integer.parseInt(o.toString()); } catch (Exception e) { return 0; } }
    private String str(Object o) { return o == null ? "" : o.toString().trim(); }

    private String normalizePaymentStatus(String s) {
        if (s == null || s.isEmpty()) return "PENDING";
        String val = s.toUpperCase();
        if (val.contains("PAID") || val.contains("SUCCESS")) return "PAID";
        if (val.contains("FAILED")) return "FAILED";
        return "PENDING";
    }

    private java.sql.Date parseSqlDate(Object val) {
        if (val == null) return null;
        String input = val.toString().trim().replace("/", "-").replace(".", "-");
        try {
            if (input.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) return java.sql.Date.valueOf(input);
            if (input.matches("\\d{1,2}-\\d{1,2}-\\d{4}")) {
                String[] p = input.split("-");
                return java.sql.Date.valueOf(p[2] + "-" + String.format("%02d", Integer.parseInt(p[1])) + "-" + String.format("%02d", Integer.parseInt(p[0])));
            }
        } catch (Exception ignored) {}
        return null;
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
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
    }

    private String json(String k, String v) { return "{\"" + k + "\":\"" + v + "\"}"; }
    private String json(String k, String v, String k2, String v2) { return "{\"" + k + "\":\"" + v + "\",\"" + k2 + "\":\"" + v2 + "\"}"; }
}