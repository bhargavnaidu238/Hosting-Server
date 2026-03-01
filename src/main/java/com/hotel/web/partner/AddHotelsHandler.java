package com.hotel.web.partner;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import com.hotel.utilities.DbConfig;
import com.hotel.server.HotelBookingServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class AddHotelsHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public AddHotelsHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    private static final Set<String> VALID_CUSTOMIZATION = Set.of("Yes", "No");
    private static final Set<String> VALID_STATUS = Set.of("Active", "Inactive");

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
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String body = readRequestBody(exchange);
        Map<String, String> params = parseForm(body);

        String hotelId = params.get("hotel_id");
        boolean isUpdate = hotelId != null && !hotelId.trim().isEmpty() && hotelExists(hotelId);

        if (!isUpdate) {
            hotelId = "HOTEL_" + System.currentTimeMillis();
        }

        try {
            // ============================================================
            // ✅ IMAGE PROCESSING LOGIC
            // ============================================================
            if (params.containsKey("images")) {
                try {
                    JSONObject json = new JSONObject(params.get("images"));
                    List<String> savedUrls = new ArrayList<>();
                    
                    // Detect environment: PORT is set by Render
                    boolean isProduction = System.getenv("PORT") != null;

                    for (String category : json.keySet()) {
                        String safeCategory = category.replaceAll("[/\\\\]", "_");
                        JSONArray arr = json.getJSONArray(category);

                        for (int i = 0; i < arr.length(); i++) {
                            String base64Data = arr.getString(i);
                            
                            // Strip metadata headers (e.g. "data:image/png;base64,")
                            if (base64Data.contains(",")) {
                                base64Data = base64Data.split(",")[1];
                            }
                            
                            byte[] data = Base64.getDecoder().decode(base64Data);
                            String fileName = hotelId + "_" + safeCategory + "_" + System.currentTimeMillis() + "_" + i + ".jpg";
                            
                            String finalUrl;
                            if (isProduction) {
                                // Production: Upload to Supabase using Server utility
                                finalUrl = HotelBookingServer.uploadToSupabase(data, fileName);
                            } else {
                                // Local: Save to local filesystem folder
                                File dir = new File(dbConfig.getHotelImagesPath() + File.separator + hotelId + File.separator + safeCategory);
                                if (!dir.exists()) dir.mkdirs();
                                File f = new File(dir, fileName);
                                Files.write(f.toPath(), data);
                                finalUrl = "http://localhost:8080/hotel_images/" + hotelId + "/" + safeCategory + "/" + fileName;
                            }
                            savedUrls.add(finalUrl);
                        }
                    }
                    // Store joined URLs in params to be picked up by the DB methods
                    if (!savedUrls.isEmpty()) {
                        params.put("hotel_images", String.join(",", savedUrls));
                    }
                } catch (Exception imgEx) {
                    System.err.println("Error processing images: " + imgEx.getMessage());
                    // We continue the flow so the text data can still be saved
                }
            }

            // ============================================================
            // ✅ DATABASE PERSISTENCE
            // ============================================================
            boolean success = isUpdate
                    ? updateHotelInDB(hotelId, params)
                    : addHotelToDB(hotelId, params);

            if (success) {
                String msg = isUpdate ? "Hotel updated successfully!" : "Hotel added successfully!";
                sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"" + msg + "\"}");
            } else {
                sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"Failed to save hotel to database.\"}");
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private boolean hotelExists(String hotelId) {
        String sql = "SELECT COUNT(*) FROM hotels_info WHERE hotel_id = ?";
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, hotelId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) return rs.getInt(1) > 0;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean addHotelToDB(String hotelId, Map<String, String> params) throws SQLException {
        String sql = "INSERT INTO hotels_info (" +
                "hotel_id, partner_id, hotel_name, hotel_type, room_type, address, city, state, country, pincode," +
                "hotel_location, total_rooms, available_rooms, room_price, amenities, description, policies, rating," +
                "hotel_contact, about_this_property, hotel_images, customization, status)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            setHotelParamsForInsert(stmt, hotelId, params);
            return stmt.executeUpdate() > 0;
        }
    }

    private boolean updateHotelInDB(String hotelId, Map<String, String> params) throws SQLException {
        String sql = "UPDATE hotels_info SET " +
                "hotel_name=?, hotel_type=?, room_type=?, address=?, city=?, state=?, country=?, pincode=?," +
                "hotel_location=?, total_rooms=?, available_rooms=?, room_price=?, amenities=?, description=?, policies=?," +
                "rating=?, hotel_contact=?, about_this_property=?, hotel_images=?, customization=?, status=? " +
                "WHERE hotel_id=?";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            setHotelParamsForUpdate(stmt, params);
            stmt.setString(22, hotelId);
            return stmt.executeUpdate() > 0;
        }
    }

    private void setHotelParamsForInsert(PreparedStatement stmt, String hotelId, Map<String, String> params) throws SQLException {
        stmt.setString(1, hotelId);
        stmt.setString(2, params.getOrDefault("partner_id", ""));
        stmt.setString(3, params.getOrDefault("hotel_name", ""));
        stmt.setString(4, params.getOrDefault("hotel_type", ""));
        stmt.setString(5, params.getOrDefault("room_type", "Standard"));
        stmt.setString(6, params.getOrDefault("address", ""));
        stmt.setString(7, params.getOrDefault("city", ""));
        stmt.setString(8, params.getOrDefault("state", ""));
        stmt.setString(9, params.getOrDefault("country", ""));
        stmt.setString(10, params.getOrDefault("pincode", ""));
        stmt.setString(11, params.getOrDefault("hotel_location", ""));
        stmt.setInt(12, Integer.parseInt(params.getOrDefault("total_rooms", "0")));
        stmt.setInt(13, Integer.parseInt(params.getOrDefault("available_rooms", params.getOrDefault("total_rooms", "0"))));
        stmt.setString(14, params.getOrDefault("room_price", ""));
        stmt.setString(15, params.getOrDefault("amenities", ""));
        stmt.setString(16, params.getOrDefault("description", ""));
        stmt.setString(17, params.getOrDefault("policies", ""));
        stmt.setDouble(18, Double.parseDouble(params.getOrDefault("rating", "0.0")));
        stmt.setString(19, params.getOrDefault("hotel_contact", ""));
        stmt.setString(20, params.getOrDefault("about_this_property", ""));
        stmt.setString(21, params.get("hotel_images")); // Pull generated URL string

        String customization = params.getOrDefault("customization", "No");
        stmt.setObject(22, VALID_CUSTOMIZATION.contains(customization) ? customization : "No", Types.OTHER);

        String status = params.getOrDefault("status", "Active");
        stmt.setObject(23, VALID_STATUS.contains(status) ? status : "Active", Types.OTHER);
    }

    private void setHotelParamsForUpdate(PreparedStatement stmt, Map<String, String> params) throws SQLException {
        stmt.setString(1, params.getOrDefault("hotel_name", ""));
        stmt.setString(2, params.getOrDefault("hotel_type", ""));
        stmt.setString(3, params.getOrDefault("room_type", "Standard"));
        stmt.setString(4, params.getOrDefault("address", ""));
        stmt.setString(5, params.getOrDefault("city", ""));
        stmt.setString(6, params.getOrDefault("state", ""));
        stmt.setString(7, params.getOrDefault("country", ""));
        stmt.setString(8, params.getOrDefault("pincode", ""));
        stmt.setString(9, params.getOrDefault("hotel_location", ""));
        stmt.setInt(10, Integer.parseInt(params.getOrDefault("total_rooms", "0")));
        stmt.setInt(11, Integer.parseInt(params.getOrDefault("available_rooms", params.getOrDefault("total_rooms", "0"))));
        stmt.setString(12, params.getOrDefault("room_price", ""));
        stmt.setString(13, params.getOrDefault("amenities", ""));
        stmt.setString(14, params.getOrDefault("description", ""));
        stmt.setString(15, params.getOrDefault("policies", ""));
        stmt.setDouble(16, Double.parseDouble(params.getOrDefault("rating", "0.0")));
        stmt.setString(17, params.getOrDefault("hotel_contact", ""));
        stmt.setString(18, params.getOrDefault("about_this_property", ""));
        stmt.setString(19, params.get("hotel_images"));

        String customization = params.getOrDefault("customization", "No");
        stmt.setObject(20, VALID_CUSTOMIZATION.contains(customization) ? customization : "No", Types.OTHER);

        String status = params.getOrDefault("status", "Active");
        stmt.setObject(21, VALID_STATUS.contains(status) ? status : "Active", Types.OTHER);
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
                map.put(URLDecoder.decode(parts[0], "UTF-8"), URLDecoder.decode(parts[1], "UTF-8"));
            }
        }
        return map;
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}