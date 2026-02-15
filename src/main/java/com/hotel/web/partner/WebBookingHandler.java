package com.hotel.web.partner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.utilities.DbConfig;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

public class WebBookingHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public WebBookingHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        // CORS preflight
        if (method.equalsIgnoreCase("OPTIONS")) {
            addCORSHeaders(exchange);
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();

        if (path.endsWith("/webgetPartnerBookings")) {
            handleGetBookings(exchange);
        } else if (path.endsWith("/webcancelBooking")) {
            handleCancelBooking(exchange);
        } else if (path.endsWith("/webupdateBookingStatus")) {
            handleUpdateBookingStatus(exchange);
        } else {
            exchange.sendResponseHeaders(404, -1);
        }
    }

    private void addCORSHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private Map<String, String> parsePostBody(HttpExchange exchange) throws IOException {
        Map<String, String> map = new HashMap<>();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);

            if (!sb.toString().isEmpty()) {
                for (String pair : sb.toString().split("&")) {
                    String[] parts = pair.split("=", 2);
                    if (parts.length == 2) {
                        map.put(
                                URLDecoder.decode(parts[0], "UTF-8").trim(),
                                URLDecoder.decode(parts[1], "UTF-8").trim()
                        );
                    }
                }
            }
        }
        return map;
    }

    private String getQueryParam(HttpExchange exchange, String key) throws UnsupportedEncodingException {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return "";

        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length == 2 && pair[0].equalsIgnoreCase(key)) {
                return URLDecoder.decode(pair[1], "UTF-8").trim();
            }
        }
        return "";
    }

    // ========================= GET BOOKINGS =========================

    private void handleGetBookings(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        addCORSHeaders(exchange);
        String partnerId = getQueryParam(exchange, "partnerId");

        List<Map<String, String>> bookings = new ArrayList<>();

        String sql = """
                SELECT partner_id, hotel_id, booking_id, hotel_name, hotel_type, guest_name,
                       email, user_id, check_in_date, check_out_date, guest_count, adults,
                       children, total_rooms_booked, total_days_at_stay, room_price_per_day,
                       all_days_price, gst, original_amount, payment_method_type,
                       hotel_address, booking_status, hotel_contact, payment_status,
                       wallet_used, wallet_amount_deducted, coupon_code
                FROM bookings_info
                WHERE partner_id = ?
                """;

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, partnerId);

            try (ResultSet rs = stmt.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        String col = meta.getColumnName(i);
                        String val = rs.getString(i);
                        row.put(col, val != null ? val : "");
                    }
                    bookings.add(row);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        String json = new ObjectMapper().writeValueAsString(bookings);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, json.getBytes(StandardCharsets.UTF_8).length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ========================= CANCEL BOOKING =========================

    private void handleCancelBooking(HttpExchange exchange) throws IOException {
        addCORSHeaders(exchange);

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        Map<String, String> params = parsePostBody(exchange);
        String bookingId = params.getOrDefault("bookingId", "");
        boolean success = false;

        if (!bookingId.isEmpty()) {
            String sql = "UPDATE bookings_info SET booking_status = 'CANCELLED' WHERE booking_id = ?";

            try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, bookingId);
                success = stmt.executeUpdate() > 0;

            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        String response = "{\"status\":\"" + (success ? "success" : "failed") + "\"}";
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ========================= UPDATE BOOKING STATUS =========================

    private void handleUpdateBookingStatus(HttpExchange exchange) throws IOException {
        addCORSHeaders(exchange);

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        Map<String, String> params = parsePostBody(exchange);
        if (params.isEmpty()) {
            params.put("bookingId", getQueryParam(exchange, "bookingId"));
            params.put("status", getQueryParam(exchange, "status"));
        }

        String bookingId = params.getOrDefault("bookingId", "").trim();
        String newStatus = params.getOrDefault("status", "").trim().toUpperCase(); // ✅ FORCE UPPERCASE

        boolean success = false;
        String message = "";

        if (!bookingId.isEmpty() && !newStatus.isEmpty()) {

            String fetchSql = "SELECT booking_status, check_out_date FROM bookings_info WHERE booking_id = ?";
            String updateSql = "UPDATE bookings_info SET booking_status = ? WHERE booking_id = ?";

            try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
                 PreparedStatement fetchStmt = conn.prepareStatement(fetchSql)) {

                fetchStmt.setString(1, bookingId);

                String currentStatus = "";
                LocalDate checkOutDate = null;

                try (ResultSet rs = fetchStmt.executeQuery()) {
                    if (rs.next()) {
                        currentStatus = rs.getString("booking_status").toUpperCase();
                        String checkOut = rs.getString("check_out_date");
                        if (checkOut != null && !checkOut.isEmpty()) {
                            checkOutDate = LocalDate.parse(checkOut);
                        }
                    }
                }

                boolean allowed = false;
                LocalDate today = LocalDate.now();

                switch (newStatus) {
                    case "CONFIRMED":
                        allowed = "PENDING".equals(currentStatus);
                        break;
                    case "CANCELLED":
                        allowed = "PENDING".equals(currentStatus) || "CONFIRMED".equals(currentStatus);
                        break;
                    case "COMPLETED":
                        allowed = "CONFIRMED".equals(currentStatus)
                                && checkOutDate != null
                                && !checkOutDate.isAfter(today);
                        break;
                }

                if (allowed) {
                    try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                        updateStmt.setString(1, newStatus); // ✅ ALWAYS UPPERCASE IN DB
                        updateStmt.setString(2, bookingId);
                        success = updateStmt.executeUpdate() > 0;
                        message = success ? "Status updated successfully" : "Update failed";
                    }
                } else {
                    message = "Action not allowed";
                }

            } catch (SQLException e) {
                e.printStackTrace();
                message = "Database error";
            }
        }

        String response = "{\"status\":\"" + (success ? "success" : "failed") +
                "\",\"message\":\"" + message + "\"}";

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes(StandardCharsets.UTF_8));
        }
    }
}
