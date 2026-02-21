package com.hotel.web.partner;

import com.hotel.utilities.DbConfig;
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

        // ✅ CORS HEADERS (UNCHANGED)
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

        String body = readRequestBody(exchange);
        Map<String, String> params;

        try {
            params = parseForm(body);
        } catch (Exception e) {
            sendResponse(exchange, 400, jsonError("Invalid form encoding"));
            return;
        }

        // ===============================
        // ✅ FIX 1: Validate partner_id BEFORE DB
        // ===============================
        String partnerId = params.get("partner_id");
        if (partnerId == null || partnerId.trim().isEmpty()) {
            sendResponse(exchange, 400, jsonError("partner_id is required"));
            return;
        }

        // ===============================
        // IMAGE HANDLING (UNCHANGED LOGIC)
        // ===============================
        try {
            if (params.containsKey("images") && !params.get("images").trim().isEmpty()) {

                JSONObject json = new JSONObject(params.get("images"));
                List<String> savedUrls = new ArrayList<>();

                String pgId = params.getOrDefault("pg_id", "").trim();
                if (pgId.isEmpty()) {
                    pgId = "PG_" + System.currentTimeMillis();
                    params.put("pg_id", pgId);
                }

                for (String category : json.keySet()) {

                    String safeCategory = category.replaceAll("[/\\\\]", "_");

                    File dir = new File(
                            dbConfig.getHotelImagesPath() +
                                    File.separator + pgId +
                                    File.separator + safeCategory
                    );

                    if (!dir.exists()) dir.mkdirs();

                    JSONArray arr = json.getJSONArray(category);

                    for (int i = 0; i < arr.length(); i++) {
                        String base64 = arr.getString(i);
                        if (base64 == null || base64.trim().isEmpty()) continue;

                        byte[] data = Base64.getDecoder().decode(base64);

                        String fileName = safeCategory + "_" + i + ".jpg";
                        File f = new File(dir, fileName);

                        try (OutputStream os = Files.newOutputStream(
                                f.toPath(),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING
                        )) {
                            os.write(data);
                        }

                        String url = "http://localhost:8080/hotel_images/" +
                                pgId + "/" + safeCategory + "/" + fileName;

                        savedUrls.add(url);
                    }
                }

                if (!savedUrls.isEmpty()) {
                    params.put("pg_images", String.join(",", savedUrls));
                }
            }
        } catch (Exception e) {
            e.printStackTrace(); // Do not fail full request
        }

        // ===============================
        // UPSERT LOGIC
        // ===============================
        String pgId = params.getOrDefault("pg_id", "").trim();
        boolean isUpdate = false;

        if (!pgId.isEmpty()) {
            isUpdate = pgExists(pgId);
        } else {
            pgId = "PG_" + System.currentTimeMillis();
            params.put("pg_id", pgId);
        }

        try {

            boolean success = isUpdate ?
                    updatePGInDB(pgId, params) :
                    addPGToDB(pgId, params);

            if (success) {
                sendResponse(exchange, 200,
                        "{\"status\":\"success\",\"message\":\"" +
                                (isUpdate ? "PG updated successfully!" : "PG added successfully!") +
                                "\"}");
            } else {
                sendResponse(exchange, 500, jsonError("Failed to save PG"));
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, jsonError(e.getMessage()));
        }
    }

    // ======================================================
    // DB METHODS
    // ======================================================

    private boolean pgExists(String pgId) {

        String sql = "SELECT COUNT(*) FROM paying_guest_info WHERE pg_id = ?";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement st = conn.prepareStatement(sql)) {

            st.setString(1, pgId);

            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) return rs.getInt(1) > 0;
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean addPGToDB(String pgId, Map<String, String> params) throws SQLException {

    	String sql = "INSERT INTO paying_guest_info (" +
    	        "pg_id, partner_id, pg_name, pg_type, room_type, address, city, state, country, pincode, " +
    	        "total_single_sharing_rooms, total_double_sharing_rooms, total_three_sharing_rooms, " +
    	        "total_four_sharing_rooms, total_five_sharing_rooms, hotel_location, available_rooms, room_price, " +
    	        "amenities, description, policies, rating, pg_contact, about_this_pg, pg_images, status" +
    	        ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::pg_status_enum)";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            setParams(stmt, pgId, params, false);
            return stmt.executeUpdate() > 0;
        }
    }

    private boolean updatePGInDB(String pgId, Map<String, String> params) throws SQLException {

        String sql = "UPDATE paying_guest_info SET " +
                "pg_name=?, partner_id=?, pg_type=?, room_type=?, address=?, city=?, state=?, country=?, pincode=?, " +
                "total_single_sharing_rooms=?, total_double_sharing_rooms=?, total_three_sharing_rooms=?, " +
                "total_four_sharing_rooms=?, total_five_sharing_rooms=?, hotel_location=?, available_rooms=?, room_price=?, " +
                "amenities=?, description=?, policies=?, rating=?, pg_contact=?, about_this_pg=?, pg_images=?, status=? " +
                "WHERE pg_id=?";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            setParams(stmt, pgId, params, true);
            return stmt.executeUpdate() > 0;
        }
    }

    // ======================================================
    // PARAM BINDING (FIXED)
    // ======================================================

    private void setParams(PreparedStatement stmt, String pgId,
                           Map<String, String> params,
                           boolean isUpdate) throws SQLException {

        int i = 1;

        if (!isUpdate) {
            stmt.setString(i++, pgId);
        }

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

        // ✅ FIX 2: Protect against rating overflow
        double rating = parseDoubleSafe(params.get("rating"));
        if (rating < 0) rating = 0;
        if (rating > 10) rating = 10;
        stmt.setDouble(i++, rating);

        stmt.setString(i++, params.getOrDefault("pg_contact", ""));
        stmt.setString(i++, params.getOrDefault("about_this_pg", ""));
        stmt.setString(i++, params.getOrDefault("pg_images", null));

        // ✅ FIX 3: Enforce valid enum values
        String status = params.getOrDefault("status", "Active");
        if (!status.equals("Active") && !status.equals("Inactive")) {
            status = "Active";
        }
        stmt.setString(i++, status);

        if (isUpdate) {
            stmt.setString(i, pgId);
        }
    }

    // ======================================================
    // UTILITIES
    // ======================================================

    private int parseIntSafe(String s) {
        try {
            return (s == null || s.trim().isEmpty()) ? 0 : Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private double parseDoubleSafe(String s) {
        try {
            return (s == null || s.trim().isEmpty()) ? 0.0 : Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(
                             exchange.getRequestBody(),
                             StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
                sb.append(line);

            return sb.toString();
        }
    }

    private Map<String, String> parseForm(String body) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;

        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                map.put(
                        URLDecoder.decode(parts[0], "UTF-8"),
                        URLDecoder.decode(parts[1], "UTF-8")
                );
            }
        }
        return map;
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String message)
            throws IOException {

        exchange.getResponseHeaders()
                .set("Content-Type", "application/json; charset=UTF-8");

        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String jsonError(String msg) {
        return "{\"status\":\"error\",\"message\":\"" +
                msg.replace("\"", "\\\"") +
                "\"}";
    }
}