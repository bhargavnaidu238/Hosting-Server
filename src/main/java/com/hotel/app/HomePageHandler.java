package com.hotel.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class HomePageHandler implements HttpHandler {

    private final DbConfig dbConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HomePageHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, 405, "{\"error\":\"Only GET allowed\"}");
            return;
        }

        URI requestURI = exchange.getRequestURI();
        String queryParams = requestURI.getQuery();

        String hotelType = null;
        String searchQuery = null;

        if (queryParams != null && !queryParams.isEmpty()) {
            for (String param : queryParams.split("&")) {
                if (param.isBlank()) continue;
                String[] pair = param.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
                
                if ("type".equalsIgnoreCase(key)) {
                    hotelType = value.trim();
                } else if ("query".equalsIgnoreCase(key) || "q".equalsIgnoreCase(key)) {
                    searchQuery = value.trim();
                }
            }
        }

        // --- THE FIX: SMART ROUTING ---
        // If we only have a searchQuery (from the Flutter search bar), 
        // determine if it's a CATEGORY or a NAME.
        if (searchQuery != null && hotelType == null) {
            String input = searchQuery.toLowerCase().trim();
            List<String> categories = Arrays.asList("hotel", "resort", "lodge", "pg", "payingguest");
            
            // Check for plural versions
            String singularInput = (input.endsWith("s") && input.length() > 3) 
                                   ? input.substring(0, input.length() - 1) 
                                   : input;

            if (categories.contains(singularInput)) {
                // Scenario 1: User searched for a category (e.g., "Hotels" or "Resort")
                hotelType = singularInput;
                searchQuery = null; // Clear query so we show ALL of that category
            } else {
                // Scenario 2: User searched for a specific name (e.g., "Grand Hyatt")
                // Keep searchQuery as is, hotelType remains null
            }
        }

        String normalizedType = (hotelType == null) ? "" : hotelType.replaceAll("[_\\-\\s]", "").toLowerCase();

        if ("payingguest".equals(normalizedType) || "pg".equals(normalizedType)) {
            handlePayingGuestRequest(exchange, searchQuery);
            return;
        }

        handleHotelRequest(exchange, hotelType, searchQuery);
    }

    private void handleHotelRequest(HttpExchange exchange, String hotelType, String searchQuery) throws IOException {
        List<Map<String, Object>> hotels = new ArrayList<>();
        String baseSql = """
            SELECT hotel_id, partner_id, hotel_name, hotel_type, room_type,
                   address, city, state, country, pincode, hotel_location,
                   total_rooms, available_rooms, room_Price, amenities,
                   description, policies, rating, hotel_contact,
                   about_this_property, hotel_images, customization, status
            FROM hotels_info
            WHERE status = 'Active'
            """;

        StringBuilder sql = new StringBuilder(baseSql);
        if (hotelType != null && !hotelType.isBlank()) {
            sql.append(" AND LOWER(hotel_type) = ?");
        }
        if (searchQuery != null && !searchQuery.isBlank()) {
            sql.append("""
                AND (
                    LOWER(hotel_name) LIKE ?
                    OR LOWER(city) LIKE ?
                    OR LOWER(state) LIKE ?
                    OR LOWER(country) LIKE ?
                )
            """);
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            int idx = 1;
            if (hotelType != null && !hotelType.isBlank()) {
                stmt.setString(idx++, hotelType.toLowerCase());
            }
            if (searchQuery != null && !searchQuery.isBlank()) {
                String p = "%" + searchQuery.toLowerCase() + "%";
                for (int i = 0; i < 4; i++) stmt.setString(idx++, p);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> hotel = new LinkedHashMap<>();
                    hotel.put("hotel_id", rs.getString("hotel_id"));
                    hotel.put("hotel_name", rs.getString("hotel_name"));
                    hotel.put("hotel_type", rs.getString("hotel_type"));
                    hotel.put("city", rs.getString("city"));
                    hotel.put("room_price", rs.getObject("room_price"));
                    hotel.put("hotel_images", buildImageString(rs.getString("hotel_images")));
                    // ... (Add other fields as needed for your UI)
                    hotels.add(hotel);
                }
            }
            sendJsonResponse(exchange, 200, objectMapper.writeValueAsString(hotels));
        } catch (Exception e) {
            e.printStackTrace();
            sendJsonResponse(exchange, 500, "{\"error\":\"Internal error\"}");
        }
    }

    private void handlePayingGuestRequest(HttpExchange exchange, String searchQuery) throws IOException {
        List<Map<String, Object>> pgs = new ArrayList<>();
        String baseSql = "SELECT * FROM paying_guest_info WHERE status = 'Active'";
        StringBuilder sql = new StringBuilder(baseSql);

        if (searchQuery != null && !searchQuery.isBlank()) {
            sql.append(" AND (LOWER(pg_name) LIKE ? OR LOWER(city) LIKE ?)");
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            if (searchQuery != null && !searchQuery.isBlank()) {
                String p = "%" + searchQuery.toLowerCase() + "%";
                stmt.setString(1, p);
                stmt.setString(2, p);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> pg = new LinkedHashMap<>();
                    pg.put("pg_name", rs.getString("pg_name"));
                    pg.put("pg_images", buildImageString(rs.getString("pg_images")));
                    pgs.add(pg);
                }
            }
            sendJsonResponse(exchange, 200, objectMapper.writeValueAsString(pgs));
        } catch (Exception e) {
            sendJsonResponse(exchange, 500, "{\"error\":\"Internal error\"}");
        }
    }

    private String buildImageString(String raw) {
        if (raw == null || raw.isBlank()) return "";
        List<String> processedList = new ArrayList<>();
        String[] parts = raw.split(",");
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (t.toLowerCase().startsWith("http")) processedList.add(t);
            else {
                String baseUrl = dbConfig.getImageBaseUrl();
                if (!baseUrl.endsWith("/")) baseUrl += "/";
                processedList.add(baseUrl + t.replaceAll("^/+", ""));
            }
        }
        return String.join(",", processedList);
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private void sendJsonResponse(HttpExchange exchange, int status, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }
}