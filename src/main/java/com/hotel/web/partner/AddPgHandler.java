package com.hotel.web.partner;

import com.hotel.utilities.DbConfig;
import com.hotel.server.HotelBookingServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.util.*;

public class AddPgHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public AddPgHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        // ✅ CORS HEADERS
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // ✅ MEMORY SAFE READING
        String body = readRequestBody(exchange);
        Map<String, String> params;

        try {
            params = parseForm(body);
        } catch (Exception e) {
            sendResponse(exchange, 400, jsonError("Invalid form encoding: " + e.getMessage()));
            return;
        }

        String partnerId = params.get("partner_id");
        if (partnerId == null || partnerId.trim().isEmpty()) {
            sendResponse(exchange, 400, jsonError("partner_id is required"));
            return;
        }

        // ============================================================
        // ✅ UPDATED IMAGE HANDLING (Supports both Base64 and URL List)
        // ============================================================
        try {
            String pgId = params.getOrDefault("pg_id", "").trim();
            if (pgId.isEmpty()) {
                pgId = "PG_" + System.currentTimeMillis();
                params.put("pg_id", pgId);
            }

            // Detect environment (Render sets PORT)
            boolean isProduction = System.getenv("PORT") != null;

            // Scenario A: Frontend sent Base64 (Legacy/Postman fallback)
            if (params.containsKey("images") && !params.get("images").trim().isEmpty()) {
                JSONObject json = new JSONObject(params.get("images"));
                List<String> savedUrls = new ArrayList<>();

                for (String category : json.keySet()) {
                    String safeCategory = category.replaceAll("[/\\\\]", "_");
                    JSONArray arr = json.getJSONArray(category);

                    for (int i = 0; i < arr.length(); i++) {
                        String base64 = arr.getString(i);
                        if (base64 == null || base64.trim().isEmpty()) continue;
                        if (base64.contains(",")) base64 = base64.split(",")[1];

                        byte[] data = Base64.getDecoder().decode(base64);
                        String fileName = pgId + "_" + safeCategory + "_" + System.currentTimeMillis() + "_" + i + ".jpg";

                        if (isProduction) {
                            savedUrls.add(HotelBookingServer.uploadToSupabase(data, fileName));
                        } else {
                            File dir = new File(dbConfig.getHotelImagesPath() + File.separator + pgId + File.separator + safeCategory);
                            if (!dir.exists()) dir.mkdirs();
                            File f = new File(dir, fileName);
                            Files.write(f.toPath(), data);
                            savedUrls.add("http://localhost:8080/hotel_images/" + pgId + "/" + safeCategory + "/" + fileName);
                        }
                    }
                }
                if (!savedUrls.isEmpty()) {
                    params.put("hotel_images", String.join(",", savedUrls));
                }
            }
            // Scenario B: Frontend already uploaded (Immediate Upload Strategy)
            // Logic handled by the "hotel_images" parameter being passed directly to DB
        } catch (Exception e) {
            System.err.println("Non-critical Image Processing Error: " + e.getMessage());
        }

        // ===============================
        // UPSERT LOGIC
        // ===============================
        String pgId = params.get("pg_id");
        boolean isUpdate = pgExists(pgId);

        try {
            boolean success = isUpdate ? updatePGInDB(pgId, params) : addPGToDB(pgId, params);

            if (success) {
                sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"" +
                        (isUpdate ? "PG updated successfully!" : "PG added successfully!") + "\"}");
            } else {
                sendResponse(exchange, 500, jsonError("Failed to save PG to Database"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, jsonError(e.getMessage()));
        }
    }

    private boolean pgExists(String pgId) {
        String sql = "SELECT COUNT(*) FROM paying_guest_info WHERE pg_id = ?";
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, pgId);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) return rs.getInt(1) > 0;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    private boolean addPGToDB(String pgId, Map<String, String> params) throws SQLException {
        String sql = "INSERT INTO paying_guest_info (pg_id, partner_id, pg_name, pg_type, room_type, address, city, state, country, pincode, total_single_sharing_rooms, total_double_sharing_rooms, total_three_sharing_rooms, total_four_sharing_rooms, total_five_sharing_rooms, hotel_location, available_rooms, room_price, amenities, description, policies, rating, pg_contact, about_this_pg, pg_images, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::pg_status_enum)";
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            setParams(stmt, pgId, params, false);
            return stmt.executeUpdate() > 0;
        }
    }

    private boolean updatePGInDB(String pgId, Map<String, String> params) throws SQLException {
        String sql = "UPDATE paying_guest_info SET pg_name=?, partner_id=?, pg_type=?, room_type=?, address=?, city=?, state=?, country=?, pincode=?, total_single_sharing_rooms=?, total_double_sharing_rooms=?, total_three_sharing_rooms=?, total_four_sharing_rooms=?, total_five_sharing_rooms=?, hotel_location=?, available_rooms=?, room_price=?, amenities=?, description=?, policies=?, rating=?, pg_contact=?, about_this_pg=?, pg_images=?, status=? WHERE pg_id=?";
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            setParams(stmt, pgId, params, true);
            return stmt.executeUpdate() > 0;
        }
    }

    private void setParams(PreparedStatement stmt, String pgId, Map<String, String> params, boolean isUpdate) throws SQLException {
        int i = 1;
        if (!isUpdate) stmt.setString(i++, pgId);

        stmt.setString(i++, params.get("partner_id"));
        stmt.setString(i++, params.getOrDefault("pg_name", ""));
        stmt.setString(i++, params.getOrDefault("pg_type", ""));
        stmt.setString(i++, params.getOrDefault("room_type", ""));
        stmt.setString(i++, params.getOrDefault("address", ""));
        stmt.setString(i++, params.getOrDefault("city", ""));
        stmt.setString(i++, params.getOrDefault("state", ""));
        stmt.setString(i++, params.getOrDefault("country", ""));
        stmt.setString(i++, params.getOrDefault("pincode", ""));

        stmt.setInt(i++, parseIntSafe(params.get("total_single_sharing_rooms")));
        stmt.setInt(i++, parseIntSafe(params.get("total_double_sharing_rooms")));
        stmt.setInt(i++, parseIntSafe(params.get("total_three_sharing_rooms")));
        stmt.setInt(i++, parseIntSafe(params.get("total_four_sharing_rooms")));
        stmt.setInt(i++, parseIntSafe(params.get("total_five_sharing_rooms")));

        stmt.setString(i++, params.getOrDefault("hotel_location", ""));
        stmt.setInt(i++, parseIntSafe(params.get("available_rooms")));
        stmt.setString(i++, params.getOrDefault("room_price", ""));
        stmt.setString(i++, params.getOrDefault("amenities", ""));
        stmt.setString(i++, params.getOrDefault("description", ""));
        stmt.setString(i++, params.getOrDefault("policies", ""));

        stmt.setDouble(i++, parseDoubleSafe(params.get("rating")));
        stmt.setString(i++, params.getOrDefault("pg_contact", ""));
        stmt.setString(i++, params.getOrDefault("about_this_pg", ""));
        stmt.setString(i++, params.getOrDefault("hotel_images", "")); // Accept direct URLs

        String status = params.getOrDefault("status", "Active");
        stmt.setString(i++, (status.equals("Active") || status.equals("Inactive")) ? status : "Active");

        if (isUpdate) stmt.setString(i, pgId);
    }

    // ✅ UTILITIES: MEMORY OPTIMIZED
    private int parseIntSafe(String s) {
        try { return (s == null || s.trim().isEmpty()) ? 0 : Integer.parseInt(s.trim()); } 
        catch (Exception e) { return 0; }
    }

    private double parseDoubleSafe(String s) {
        try { return (s == null || s.trim().isEmpty()) ? 0.0 : Double.parseDouble(s.trim()); } 
        catch (Exception e) { return 0.0; }
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8), 8192)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[8192];
            int bytesRead;
            while ((bytesRead = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, bytesRead);
            }
            return sb.toString();
        }
    }

    private Map<String, String> parseForm(String body) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;
        for (String pair : body.split("&")) {
            int idx = pair.indexOf("=");
            if (idx > 0 && idx < pair.length() - 1) {
                map.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
                        URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
            }
        }
        return map;
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private String jsonError(String msg) {
        return "{\"status\":\"error\",\"message\":\"" + msg.replace("\"", "\\\"") + "\"}";
    }
}