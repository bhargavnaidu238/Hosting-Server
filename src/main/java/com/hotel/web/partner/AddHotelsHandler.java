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
                            String fileName = hotelId + "_" + safeCategory + "_" + System.currentTimeMillis() + "_" + i + ".jpg";
                            
                            String finalUrl;
                            if (isProduction) {
                                finalUrl = HotelBookingServer.uploadToSupabase(data, fileName);
                            } else {
                                File dir = new File(dbConfig.getHotelImagesPath() + File.separator + hotelId + File.separator + safeCategory);
                                if (!dir.exists()) dir.mkdirs();
                                File f = new File(dir, fileName);
                                Files.write(f.toPath(), data);
                                finalUrl = "http://localhost:8080/hotel_images/" + hotelId + "/" + safeCategory + "/" + fileName;
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
        // SQL order: partner_id(1), hotel_name(2), hotel_type(3), room_type(4), address(5), city(6), state(7), country(8), pincode(9), hotel_location(10), total_rooms(11), available_rooms(12), room_price(13), amenities(14), policies(15), avg_rating(16), hotel_contact(17), about_this_property(18), hotel_images(19), customization(20), status(21)
        String sql = "INSERT INTO hotels_info (hotel_id, partner_id, hotel_name, hotel_type, room_type, address, city, state, "
                + "country, pincode, hotel_location, total_rooms, available_rooms, room_price, amenities, policies, avg_rating, "
                + "hotel_contact, about_this_property, hotel_images, customization, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::numeric, ?, ?, ?, ?::cust_enum, ?::status_enum)";
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            setHotelParams(stmt, hotelId, params, true);
            return stmt.executeUpdate() > 0;
        }
    }

    private boolean updateHotelInDB(String hotelId, Map<String, String> params) throws SQLException {
        // Fixed Parameter sequence to match setHotelParams exactly
        String sql = "UPDATE hotels_info SET partner_id=?, hotel_name=?, hotel_type=?, room_type=?, address=?, city=?, state=?, country=?, "
                + "pincode=?, hotel_location=?, total_rooms=?, available_rooms=?, room_price=?, amenities=?, policies=?, "
                + "avg_rating=?::numeric, hotel_contact=?, about_this_property=?, hotel_images=?, customization=?::cust_enum, status=?::status_enum WHERE hotel_id=?";
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            setHotelParams(stmt, hotelId, params, false);
            return stmt.executeUpdate() > 0;
        }
    }

    private void setHotelParams(PreparedStatement stmt, String hotelId, Map<String, String> params, boolean isInsert) throws SQLException {
        int idx = 1;
        if (isInsert) {
            stmt.setString(idx++, hotelId); // idx 1
        }
        
        stmt.setString(idx++, params.getOrDefault("partner_id", "")); // idx 2
        stmt.setString(idx++, params.getOrDefault("hotel_name", "")); // idx 3
        stmt.setString(idx++, params.getOrDefault("hotel_type", "")); // idx 4
        stmt.setString(idx++, params.getOrDefault("room_type", "Standard")); // idx 5
        stmt.setString(idx++, params.getOrDefault("address", "")); // idx 6
        stmt.setString(idx++, params.getOrDefault("city", "")); // idx 7
        stmt.setString(idx++, params.getOrDefault("state", "")); // idx 8
        stmt.setString(idx++, params.getOrDefault("country", "")); // idx 9
        stmt.setString(idx++, params.getOrDefault("pincode", "")); // idx 10
        stmt.setString(idx++, params.getOrDefault("hotel_location", "")); // idx 11
        stmt.setInt(idx++, Integer.parseInt(params.getOrDefault("total_rooms", "0"))); // idx 12
        stmt.setInt(idx++, Integer.parseInt(params.getOrDefault("available_rooms", params.getOrDefault("total_rooms", "0")))); // idx 13
        stmt.setString(idx++, params.getOrDefault("room_price", "")); // idx 14
        stmt.setString(idx++, params.getOrDefault("amenities", "")); // idx 15
        stmt.setString(idx++, params.getOrDefault("policies", "")); // idx 16
        
        // Rating - idx 17 (Numeric)
        String ratingStr = params.get("avg_rating");
        if (ratingStr == null) ratingStr = params.getOrDefault("rating", "0.0");
        stmt.setDouble(idx++, Double.parseDouble(ratingStr));

        stmt.setString(idx++, params.getOrDefault("hotel_contact", "")); // idx 18
        stmt.setString(idx++, params.getOrDefault("about_this_property", "")); // idx 19
        stmt.setString(idx++, params.get("hotel_images")); // idx 20
        
        // Customization - idx 21 (Enum)
        String customization = params.getOrDefault("customization", "No");
        stmt.setString(idx++, VALID_CUSTOMIZATION.contains(customization) ? customization : "No");
        
        // Status - idx 22 (Enum)
        String status = params.getOrDefault("status", "Active");
        stmt.setString(idx++, VALID_STATUS.contains(status) ? status : "Active");
        
        if (!isInsert) {
            stmt.setString(idx, hotelId); // idx 23
        }
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