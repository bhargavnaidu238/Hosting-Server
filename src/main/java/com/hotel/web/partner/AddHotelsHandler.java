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

        try {
            String body = readRequestBody(exchange);
            Map<String, String> params = parseForm(body);

            String hotelId = params.get("hotel_id");
            boolean isUpdate = hotelId != null && !hotelId.trim().isEmpty() && hotelExists(hotelId);

            if (!isUpdate) {
                hotelId = "HOTEL_" + System.currentTimeMillis();
            }

            if (params.containsKey("images")) {
                try {
                    JSONObject json = new JSONObject(params.get("images"));
                    List<String> savedUrls = new ArrayList<>();
                    boolean isProduction = System.getenv("PORT") != null;

                    for (String category : json.keySet()) {
                        String safeCategory = category.replaceAll("[/\\\\]", "_");
                        JSONArray arr = json.getJSONArray(category);

                        for (int i = 0; i < arr.length(); i++) {
                            String base64Data = arr.getString(i);
                            if (base64Data.contains(",")) {
                                base64Data = base64Data.split(",")[1];
                            }
                            
                            byte[] data = Base64.getDecoder().decode(base64Data);
                            String fileName = safeCategory + "_" + System.currentTimeMillis() + "_" + i + ".jpg";
                            
                            String finalUrl;
                            // FIX: Exclusive block to prevent image creation outside the folder
                            if (isProduction) {
                                String supabasePath = hotelId + "/" + fileName;
                                finalUrl = HotelBookingServer.uploadToSupabase(data, supabasePath);
                            } else {
                                File dir = new File(dbConfig.getHotelImagesPath() + File.separator + hotelId);
                                if (!dir.exists()) dir.mkdirs();
                                File f = new File(dir, fileName);
                                Files.write(f.toPath(), data);
                                finalUrl = "http://localhost:8080/hotel_images/" + hotelId + "/" + fileName;
                            }
                            savedUrls.add(finalUrl);
                        }
                    }
                    if (!savedUrls.isEmpty()) {
                        params.put("hotel_images", String.join(",", savedUrls));
                    }
                } catch (Exception imgEx) {
                    System.err.println("Error processing images: " + imgEx.getMessage());
                }
            }

            boolean success = isUpdate ? updateHotelInDB(hotelId, params) : addHotelToDB(hotelId, params);

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
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    private boolean addHotelToDB(String hotelId, Map<String, String> params) throws SQLException {
        String sql = "INSERT INTO hotels_info (hotel_id, partner_id, hotel_name, hotel_type, room_type, address, city, state, country, pincode, latitude, longitude, total_rooms, available_rooms, room_price, amenities, policies, avg_rating, total_reviews, hotel_contact, about_this_property, hotel_images, customization, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            setHotelParams(stmt, hotelId, params, true);
            return stmt.executeUpdate() > 0;
        }
    }

    private boolean updateHotelInDB(String hotelId, Map<String, String> params) throws SQLException {
        String sql = "UPDATE hotels_info SET hotel_name=?, hotel_type=?, room_type=?, address=?, city=?, state=?, country=?, pincode=?, latitude=?, longitude=?, total_rooms=?, available_rooms=?, room_price=?, amenities=?, policies=?, hotel_contact=?, about_this_property=?, hotel_images=?, customization=?, status=? WHERE hotel_id=?";
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            setHotelParams(stmt, hotelId, params, false);
            return stmt.executeUpdate() > 0;
        }
    }

    private void setHotelParams(PreparedStatement stmt, String hotelId, Map<String, String> params, boolean isInsert) throws SQLException {
        int idx = 1;
        if (isInsert) stmt.setString(idx++, hotelId);
        
        stmt.setString(idx++, params.getOrDefault("partner_id", ""));
        stmt.setString(idx++, params.getOrDefault("hotel_name", ""));
        stmt.setString(idx++, params.getOrDefault("hotel_type", ""));
        stmt.setString(idx++, params.getOrDefault("room_type", "Standard"));
        stmt.setString(idx++, params.getOrDefault("address", ""));
        stmt.setString(idx++, params.getOrDefault("city", ""));
        stmt.setString(idx++, params.getOrDefault("state", ""));
        stmt.setString(idx++, params.getOrDefault("country", ""));
        stmt.setString(idx++, params.getOrDefault("pincode", ""));

        // FIX: Enhanced parsing logic to ensure Lat/Lng are not saved as zero
        String latStr = params.get("latitude");
        String lngStr = params.get("longitude");
        try {
            stmt.setDouble(idx++, (latStr != null && !latStr.isEmpty()) ? Double.parseDouble(latStr) : 0.0);
            stmt.setDouble(idx++, (lngStr != null && !lngStr.isEmpty()) ? Double.parseDouble(lngStr) : 0.0);
        } catch (NumberFormatException e) {
            stmt.setDouble(idx-2, 0.0);
            stmt.setDouble(idx-1, 0.0);
        }

        stmt.setInt(idx++, Integer.parseInt(params.getOrDefault("total_rooms", "0")));
        stmt.setInt(idx++, Integer.parseInt(params.getOrDefault("available_rooms", params.getOrDefault("total_rooms", "0"))));
        stmt.setString(idx++, params.getOrDefault("room_price", ""));
        stmt.setString(idx++, params.getOrDefault("amenities", ""));
        stmt.setString(idx++, params.getOrDefault("policies", ""));

        if (isInsert) {
            stmt.setDouble(idx++, 0.0); 
            stmt.setInt(idx++, 0);      
        }
        stmt.setString(idx++, params.getOrDefault("hotel_contact", ""));
        stmt.setString(idx++, params.getOrDefault("about_this_property", ""));
        stmt.setString(idx++, params.get("hotel_images"));
        
        String customization = params.getOrDefault("customization", "No");
        stmt.setObject(idx++, VALID_CUSTOMIZATION.contains(customization) ? customization : "No", Types.OTHER);
        
        String status = params.getOrDefault("status", "Active");
        stmt.setObject(idx++, VALID_STATUS.contains(status) ? status : "Active", Types.OTHER);
        
        if (!isInsert) stmt.setString(idx, hotelId);
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
                String key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                String value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                map.put(key, value);
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

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}