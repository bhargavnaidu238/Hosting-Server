package com.hotel.web.partner;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.*;
import java.io.*;
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
            sendResponse(exchange, 500, "status=error&message=" + escapeCell(e.getMessage()));
        }
    }

    // FETCH DATA + ADD TOTAL ROOMS FIELD
    private List<String> fetchPGsFromDB(String partnerId) throws SQLException {
        List<String> pgRows = new ArrayList<>();

        String sql =
            "SELECT pg_id, partner_id, pg_name, pg_type, room_type, address, city, state, country, pincode, " +
            "total_single_sharing_rooms, total_double_sharing_rooms, total_three_sharing_rooms, " +
            "total_four_sharing_rooms, total_five_sharing_rooms, room_price, amenities, rating, pg_contact, status " +
            "FROM paying_guest_info WHERE partner_id = ?";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, partnerId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {

                    List<String> row = new ArrayList<>();

                    // Add the original 21 fields
                    for (int i = 1; i <= 21; i++) {
                        row.add(escapeCell(rs.getString(i)));
                    }

                    // ------- NEW FIELD: TOTAL ROOMS -------
                    int single = safeInt(rs.getString(11));
                    int doubleR = safeInt(rs.getString(12));
                    int three = safeInt(rs.getString(13));
                    int four = safeInt(rs.getString(14));
                    int five = safeInt(rs.getString(15));

                    int totalRooms = single + doubleR + three + four + five;

                    row.add(String.valueOf(totalRooms)); // <--- NEW COLUMN

                    pgRows.add(String.join("|", row));
                }
            }
        }
        return pgRows;
    }

    // Helper to avoid null -> int crash
    private int safeInt(String val) {
        if (val == null || val.trim().isEmpty()) return 0;
        try {
            return Integer.parseInt(val.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== DELETE =================================
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

    // =================== UTILITIES =======================
    private String readRequestBody(HttpExchange exchange) throws IOException {
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));

        StringBuilder sb = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private Map<String, String> parseForm(String body) throws UnsupportedEncodingException {

        Map<String, String> map = new HashMap<>();

        if (body == null || body.isEmpty()) return map;

        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                String key = java.net.URLDecoder.decode(parts[0], "UTF-8").toLowerCase();
                String val = java.net.URLDecoder.decode(parts[1], "UTF-8");
                map.put(key, val);
            }
        }
        return map;
    }

    private String escapeCell(String s) {
        if (s == null) return "";
        return s.replace("&", "and")
                .replace("=", ":")
                .replace("|", "/")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private void sendResponse(HttpExchange exchange, int code, String response) throws IOException {

        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);

        exchange.sendResponseHeaders(code, bytes.length);

        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }
}
