package com.hotel.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class BookingHistoryHandler implements HttpHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DbConfig dbConfig;

    public BookingHistoryHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        switch (exchange.getRequestMethod().toUpperCase()) {
            case "GET" -> handleBookingHistory(exchange);
            case "PUT" -> handlePutRequests(exchange);
            case "POST" -> handlePostRequests(exchange); // Handle the /customize call
            default -> sendResponse(exchange, 405, json("error", "Method Not Allowed"));
        }
    }

    private void handlePostRequests(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI().normalize();
        if (uri.getPath().endsWith("/customize")) {
            handleUpdatePreferences(exchange);
        } else {
            sendResponse(exchange, 404, json("error", "Invalid API path"));
        }
    }

    private void handlePutRequests(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI().normalize();
        if (uri.getPath().endsWith("/cancel-booking")) {
            handleCancelBooking(exchange);
        } else if (uri.getPath().endsWith("/update-booking-dates")) {
            handleUpdateBookingDates(exchange);
        } else {
            sendResponse(exchange, 404, json("error", "Invalid API path"));
        }
    }

    // -------------------- SAVE PREFERENCES LOGIC --------------------
    private void handleUpdatePreferences(HttpExchange exchange) throws IOException {
        try {
            Map<String, Object> data = objectMapper.readValue(exchange.getRequestBody(), Map.class);
            String email = Objects.toString(data.get("email"), "").trim();

            if (email.isEmpty()) {
                sendResponse(exchange, 400, json("error", "Email is required"));
                return;
            }

            // 1. Fetch current preferences first to avoid overwriting
            String selectSql = "SELECT * FROM user_info WHERE user_email = ?";
            
            try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {
                Map<String, String> currentPrefs = new HashMap<>();
                try (PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
                    selectStmt.setString(1, email);
                    ResultSet rs = selectStmt.executeQuery();
                    if (rs.next()) {
                        currentPrefs.put("stay_type", rs.getString("stay_type"));
                        currentPrefs.put("meal_preference", rs.getString("meal_preference"));
                        currentPrefs.put("add_ons", rs.getString("add_ons"));
                        currentPrefs.put("travel_style", rs.getString("travel_style"));
                        currentPrefs.put("stay_preference", rs.getString("stay_preference"));
                        currentPrefs.put("for_you", rs.getString("for_you"));
                        currentPrefs.put("location_preference", rs.getString("location_preference"));
                    } else {
                        sendResponse(exchange, 404, json("error", "User profile not found"));
                        return;
                    }
                }

                // 2. Merge existing data with new data
                String sql = """
                    UPDATE user_info SET 
                        stay_type = ?, 
                        meal_preference = ?, 
                        add_ons = ?, 
                        travel_style = ?, 
                        stay_preference = ?, 
                        for_you = ?, 
                        location_preference = ?,
                        budget_min = ?,
                        budget_max = ?
                    WHERE user_email = ?
                    """;

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, mergePreferences(currentPrefs.get("stay_type"), data.get("stay_type")));
                    stmt.setString(2, mergePreferences(currentPrefs.get("meal_preference"), data.get("meal_preference")));
                    stmt.setString(3, mergePreferences(currentPrefs.get("add_ons"), data.get("add_ons")));
                    stmt.setString(4, mergePreferences(currentPrefs.get("travel_style"), data.get("travel_style")));
                    stmt.setString(5, mergePreferences(currentPrefs.get("stay_preference"), data.get("stay_preference")));
                    stmt.setString(6, mergePreferences(currentPrefs.get("for_you"), data.get("for_you")));
                    stmt.setString(7, mergePreferences(currentPrefs.get("location_preference"), data.get("location_preference")));
                    
                    // Numeric values usually overwrite rather than append
                    stmt.setObject(8, data.get("budget_min"), Types.NUMERIC);
                    stmt.setObject(9, data.get("budget_max"), Types.NUMERIC);
                    stmt.setString(10, email);

                    int updated = stmt.executeUpdate();
                    if (updated > 0) {
                        sendResponse(exchange, 200, json("success", "Preferences merged and updated successfully"));
                    } else {
                        sendResponse(exchange, 500, json("error", "Failed to update record"));
                    }
                }
            }
        } catch (Exception e) {
            sendResponse(exchange, 500, json("error", "Database Error: " + e.getMessage()));
        }
    }

    /**
     * Helper method to merge existing comma-separated strings with new values.
     * Prevents duplicate entries.
     */
    private String mergePreferences(String existing, Object incoming) {
        String newItems = Objects.toString(incoming, "").trim();
        if (newItems.isEmpty() || newItems.equals("null")) return existing;
        if (existing == null || existing.isEmpty()) return newItems;

        // Split into sets to remove duplicates automatically
        Set<String> mergedSet = new LinkedHashSet<>();
        
        // Add existing items
        for (String s : existing.split(",")) {
            if (!s.trim().isEmpty()) mergedSet.add(s.trim());
        }
        
        // Add new items
        for (String s : newItems.split(",")) {
            if (!s.trim().isEmpty()) mergedSet.add(s.trim());
        }

        return String.join(", ", mergedSet);
    }

    // -------------------- REMAINING EXISTING METHODS --------------------

    private void handleBookingHistory(HttpExchange exchange) throws IOException {
        Map<String, String> params = decodeParams(exchange.getRequestURI().getQuery());
        String email = params.getOrDefault("email", "").trim();
        String userId = params.getOrDefault("userId", "").trim();

        String sql = "SELECT * FROM bookings_info WHERE (email=? OR user_id=?) ORDER BY payment_confirmed_at DESC, check_in_date DESC";
        List<Map<String, Object>> results = new ArrayList<>();

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            stmt.setString(2, userId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) { results.add(mapRow(rs)); }
            sendResponse(exchange, 200, results);
        } catch (Exception e) {
            sendResponse(exchange, 500, json("error", e.getMessage()));
        }
    }

    private void handleUpdateBookingDates(HttpExchange exchange) throws IOException {
        Map<String, Object> data = objectMapper.readValue(exchange.getRequestBody(), Map.class);
        String bookingId = Objects.toString(data.get("booking_id"), "");
        String newIn = Objects.toString(data.get("check_in_date"), "");
        String newOut = Objects.toString(data.get("check_out_date"), "");

        String fetch = "SELECT room_price_per_day, gst FROM bookings_info WHERE booking_id=?";
        String update = "UPDATE bookings_info SET check_in_date=?, check_out_date=?, total_days_at_stay=?, final_payable_amount=? WHERE booking_id=?";

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement fStmt = conn.prepareStatement(fetch);
             PreparedStatement uStmt = conn.prepareStatement(update)) {
            fStmt.setString(1, bookingId);
            ResultSet rs = fStmt.executeQuery();
            if (rs.next()) {
                long days = ChronoUnit.DAYS.between(LocalDate.parse(newIn), LocalDate.parse(newOut));
                double price = (rs.getDouble("room_price_per_day") * days) + rs.getDouble("gst");
                uStmt.setDate(1, java.sql.Date.valueOf(newIn));
                uStmt.setDate(2, java.sql.Date.valueOf(newOut));
                uStmt.setInt(3, (int) days);
                uStmt.setDouble(4, price);
                uStmt.setString(5, bookingId);
                uStmt.executeUpdate();
                sendResponse(exchange, 200, json("success", "Dates updated"));
            }
        } catch (Exception e) { sendResponse(exchange, 500, json("error", e.getMessage())); }
    }

    private void handleCancelBooking(HttpExchange exchange) throws IOException {
        Map<String, Object> body = objectMapper.readValue(exchange.getRequestBody(), Map.class);
        String bId = Objects.toString(body.get("booking_id"), "");
        String sql = "UPDATE bookings_info SET booking_status='CANCELLED'::booking_status_enum WHERE booking_id=?";
        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, bId);
            stmt.executeUpdate();
            sendResponse(exchange, 200, json("success", "Cancelled"));
        } catch (Exception e) { sendResponse(exchange, 500, json("error", e.getMessage())); }
    }

    private Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            Object val = rs.getObject(i);
            if (val instanceof java.sql.Date d) val = d.toString();
            else if (val instanceof java.sql.Timestamp t) val = t.toString();
            row.put(meta.getColumnLabel(i), val);
        }
        return row;
    }

    private Map<String, String> decodeParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String p : query.split("&")) {
            String[] pair = p.split("=", 2);
            if (pair.length == 2) map.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8).trim());
        }
        return map;
    }

    private void sendResponse(HttpExchange ex, int code, Object body) throws IOException {
        String json = body instanceof String ? (String) body : objectMapper.writeValueAsString(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private Map<String, Object> json(String k, Object v) { return Map.of(k, v); }
}