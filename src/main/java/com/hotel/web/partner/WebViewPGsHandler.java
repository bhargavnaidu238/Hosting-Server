package com.hotel.web.partner;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class WebViewPGsHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public WebViewPGsHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "status=error&message=Method not allowed");
            return;
        }

        String body = readRequestBody(exchange);
        Map<String, String> params = parseForm(body);

        try {
            // ----- DELETE PGs -----
            if (params.containsKey("pg_ids")) {
                String idsStr = params.get("pg_ids");
                List<String> pgIds = Arrays.asList(idsStr.split(","));
                deletePGsFromDB(pgIds);
                sendResponse(exchange, 200, "status=success&data=deleted");
                return;
            }

            // ----- FETCH PGs -----
            if (params.containsKey("partner_id")) {
                String partnerId = params.get("partner_id");
                List<String> pgRows = fetchPGsFromDB(partnerId);
                String response = "status=success&data=" + String.join("\n", pgRows);
                sendResponse(exchange, 200, response);
                return;
            }

            sendResponse(exchange, 400, "status=error&message=Missing parameters");

        } catch (SQLException e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "status=error&message=" + escapeCell(e.getMessage()));
        }
    }

    private List<String> fetchPGsFromDB(String partnerId) throws SQLException {
        List<String> pgRows = new ArrayList<>();

        // Updated SQL: rating replaced with avg_rating and total_reviews
        String sql =
            "SELECT pg_id, partner_id, pg_name, pg_type, room_type, address, city, state, country, pincode, " + // 1-10
            "total_single_sharing_rooms, total_double_sharing_rooms, total_three_sharing_rooms, " + // 11-13
            "total_four_sharing_rooms, total_five_sharing_rooms, hotel_location, available_rooms, " + // 14-17
            "room_price, amenities, policies, avg_rating, total_reviews, pg_contact, about_this_pg, pg_images, status " + // 18-26
            "FROM paying_guest_info WHERE partner_id = ?";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, partnerId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    List<String> row = new ArrayList<>();

                    // Add all 26 columns from the DB result set
                    for (int i = 1; i <= 26; i++) {
                        row.add(escapeCell(rs.getString(i)));
                    }

                    // Calculate Total Rooms separately using indices 11-15
                    int total = safeInt(rs.getString(11)) + safeInt(rs.getString(12)) + 
                                safeInt(rs.getString(13)) + safeInt(rs.getString(14)) + 
                                safeInt(rs.getString(15));
                    
                    // Add Total Rooms as an EXTRA column (Index 27 for the frontend)
                    row.add(String.valueOf(total)); 

                    pgRows.add(String.join("|", row));
                }
            }
        }
        return pgRows;
    }

    private int safeInt(String val) {
        if (val == null || val.trim().isEmpty()) return 0;
        try { return Integer.parseInt(val.trim()); } catch (Exception e) { return 0; }
    }

    private void deletePGsFromDB(List<String> pgIds) throws SQLException {
        if (pgIds.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(pgIds.size(), "?"));
        String sql = "DELETE FROM paying_guest_info WHERE pg_id IN (" + placeholders + ")";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < pgIds.size(); i++) {
                stmt.setString(i + 1, pgIds.get(i));
            }
            stmt.executeUpdate();
        }
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private Map<String, String> parseForm(String body) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                map.put(URLDecoder.decode(parts[0], "UTF-8").toLowerCase(), URLDecoder.decode(parts[1], "UTF-8"));
            }
        }
        return map;
    }

    private String escapeCell(String s) {
        if (s == null) return "";
        // Sanitize string to prevent breaking the pipe-delimited format
        return s.replace("&", "and").replace("=", ":").replace("|", "/").replace("\r", " ").replace("\n", " ");
    }

    private void sendResponse(HttpExchange exchange, int code, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}