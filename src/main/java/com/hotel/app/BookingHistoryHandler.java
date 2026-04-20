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
        // Add CORS headers for all requests
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, PUT, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        switch (exchange.getRequestMethod().toUpperCase()) {
            case "GET" -> handleBookingHistory(exchange);
            case "PUT" -> handlePutRequests(exchange);
            default -> sendResponse(exchange, 405, json("error", "Method Not Allowed"));
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

    // -------------------- GET HISTORY (FIXED FOR PG VISIBILITY) --------------------
    private void handleBookingHistory(HttpExchange exchange) throws IOException {
        Map<String, String> params = decodeParams(exchange.getRequestURI().getQuery());

        String email = params.getOrDefault("email", "").trim();
        String userId = params.getOrDefault("userId", "").trim();

        if (email.isEmpty() && userId.isEmpty()) {
            sendResponse(exchange, 400, json("error", "Missing email or userId"));
            return;
        }

        // Logic Fix: Removed manual conditional filtering. Fetching all records 
        // linked to the user ensures PG records are not excluded by status/date logic.
        String sql = """
                SELECT * FROM bookings_info
                WHERE (email=? OR user_id=?)
                ORDER BY payment_confirmed_at DESC, check_in_date DESC
                """;

        List<Map<String, Object>> results = new ArrayList<>();

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, email);
            stmt.setString(2, userId);

            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                // Map the row and include all PG specific columns (months, room_price_per_month)
                results.add(mapRow(rs));
            }

            sendResponse(exchange, 200, objectMapper.writeValueAsString(results));

        } catch (Exception e) {
            sendResponse(exchange, 500, json("error", e.getMessage()));
        }
    }

    // -------------------- DATE CHANGE --------------------
    private void handleUpdateBookingDates(HttpExchange exchange) throws IOException {
        Map<String, Object> data = objectMapper.readValue(exchange.getRequestBody(), Map.class);

        String bookingId = Objects.toString(data.get("booking_id"), "");
        String newCheckIn = Objects.toString(data.get("check_in_date"), "");
        String newCheckOut = Objects.toString(data.get("check_out_date"), "");

        if (bookingId.isBlank() || newCheckIn.isBlank() || newCheckOut.isBlank()) {
            sendResponse(exchange, 400, json("error", "Missing parameters"));
            return;
        }

        String fetchSql = "SELECT room_price_per_day, gst, hotel_type FROM bookings_info WHERE booking_id=?";
        String updateSql = """
                UPDATE bookings_info SET
                check_in_date=?, 
                check_out_date=?, 
                total_days_at_stay=?, 
                final_payable_amount=?,
                booking_status='PENDING'::booking_status_enum
                WHERE booking_id=?
                """;

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement fetch = conn.prepareStatement(fetchSql);
             PreparedStatement update = conn.prepareStatement(updateSql)) {

            fetch.setString(1, bookingId);
            ResultSet rs = fetch.executeQuery();

            if (!rs.next()) {
                sendResponse(exchange, 404, json("error", "Booking not found"));
                return;
            }

            LocalDate in = LocalDate.parse(newCheckIn);
            LocalDate out = LocalDate.parse(newCheckOut);
            long days = ChronoUnit.DAYS.between(in, out);

            if (days <= 0) {
                sendResponse(exchange, 400, json("error", "Invalid stay duration"));
                return;
            }

            // Price calculation logic stays intact for Hotels
            double price = rs.getDouble("room_price_per_day") * days + rs.getDouble("gst");

            update.setDate(1, java.sql.Date.valueOf(in));
            update.setDate(2, java.sql.Date.valueOf(out));
            update.setInt(3, (int) days);
            update.setDouble(4, price);
            update.setString(5, bookingId);
            update.executeUpdate();

            sendResponse(exchange, 200, json("success", "Dates updated successfully"));

        } catch (SQLException e) {
            sendResponse(exchange, 500, json("error", e.getMessage()));
        }
    }

    // -------------------- CANCEL BOOKING --------------------
    private void handleCancelBooking(HttpExchange exchange) throws IOException {
        Map<String, Object> body = objectMapper.readValue(exchange.getRequestBody(), Map.class);
        String bookingId = Objects.toString(body.get("booking_id"), "");

        if (bookingId.isBlank()) {
            sendResponse(exchange, 400, json("error", "Missing Booking ID"));
            return;
        }

        String sql = "UPDATE bookings_info SET booking_status='CANCELLED'::booking_status_enum, refund_status='Refund Initiated' WHERE booking_id=?";

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, bookingId);
            stmt.executeUpdate();

            sendResponse(exchange, 200, json("success", "Booking cancelled"));

        } catch (SQLException e) {
            sendResponse(exchange, 500, json("error", e.getMessage()));
        }
    }

    // -------------------- MAP DB -> JSON --------------------
    private Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();

        for (int i = 1; i <= meta.getColumnCount(); i++) {
            Object value = rs.getObject(i);
            if (value instanceof java.sql.Date date)
                value = date.toLocalDate().toString();
            else if (value instanceof java.sql.Timestamp ts)
                value = ts.toString();
            
            row.put(meta.getColumnLabel(i), value);
        }
        return row;
    }

    private Map<String, String> decodeParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;

        for (String p : query.split("&")) {
            String[] pair = p.split("=", 2);
            if (pair.length == 2) {
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                        .replaceAll("[\\s\\r\\n]+$", "").trim();
                map.put(key, value);
            }
        }
        return map;
    }

    private void sendResponse(HttpExchange ex, int code, Object body) throws IOException {
        String json = body instanceof String ? (String) body : objectMapper.writeValueAsString(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { 
            os.write(bytes); 
            os.flush();
        }
    }

    private Map<String, Object> json(String k, Object v) {
        return Map.of(k, v);
    }
}