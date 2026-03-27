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
import java.util.stream.Collectors;

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
                } else if ("query".equalsIgnoreCase(key) || "q".equalsIgnoreCase(key) || "searchQuery".equalsIgnoreCase(key)) {
                    searchQuery = value.trim();
                }
            }
        }

        String normalizedType = normalizeString(hotelType);

        // SMART ROUTING: If searching for "pg" or "paying guest" keywords
        if (normalizedType.contains("payingguest") || normalizedType.contains("pg")) {
            handlePayingGuestRequest(exchange, searchQuery);
        } else if (searchQuery != null && !searchQuery.isBlank() && normalizedType.isEmpty()) {
            handleGlobalSearch(exchange, searchQuery);
        } else {
            handleHotelRequest(exchange, hotelType, searchQuery);
        }
    }

    // Helper: Normalize strings and strip trailing 's' for singular comparison
    private String normalizeString(String input) {
        if (input == null) return "";
        String clean = input.replaceAll("[_\\-\\s]", "").toLowerCase();
        if (clean.endsWith("s") && clean.length() > 3) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    private void handleGlobalSearch(HttpExchange exchange, String searchQuery) throws IOException {
        try {
            List<Map<String, Object>> results = new ArrayList<>();
            results.addAll(getHotelsData(null, searchQuery));
            results.addAll(getPGData(searchQuery));
            sendJsonResponse(exchange, 200, objectMapper.writeValueAsString(results));
        } catch (SQLException e) {
            e.printStackTrace();
            sendJsonResponse(exchange, 500, "{\"error\":\"Database error\"}");
        }
    }

    private void handleHotelRequest(HttpExchange exchange, String hotelType, String searchQuery) throws IOException {
        try {
            List<Map<String, Object>> hotels = getHotelsData(hotelType, searchQuery);
            sendJsonResponse(exchange, 200, objectMapper.writeValueAsString(hotels));
        } catch (SQLException e) {
            e.printStackTrace();
            sendJsonResponse(exchange, 500, "{\"error\":\"Database error in hotels\"}");
        }
    }

    private void handlePayingGuestRequest(HttpExchange exchange, String searchQuery) throws IOException {
        try {
            List<Map<String, Object>> pgs = getPGData(searchQuery);
            sendJsonResponse(exchange, 200, objectMapper.writeValueAsString(pgs));
        } catch (SQLException e) {
            e.printStackTrace();
            sendJsonResponse(exchange, 500, "{\"error\":\"Database error in PGs\"}");
        }
    }

    private List<Map<String, Object>> getHotelsData(String hotelType, String searchQuery) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM hotels_info WHERE status = 'Active'");
        List<String> params = new ArrayList<>();

        // 1. SMART FILTER BY CATEGORY (If user clicked a category)
        if (hotelType != null && !hotelType.isBlank()) {
            String clean = normalizeString(hotelType);
            sql.append(" AND (LOWER(hotel_type) LIKE ? OR LOWER(hotel_type) LIKE ?)");
            params.add("%" + clean + "%");
            params.add("%" + hotelType.toLowerCase() + "%");
        }

        // 2. SMART SEARCH LOGIC (Fuzzy matching on keywords)
        if (searchQuery != null && !searchQuery.isBlank()) {
            String[] tokens = searchQuery.toLowerCase().split("\\s+");
            for (String token : tokens) {
                if (token.length() < 2) continue;
                String singular = normalizeString(token);
                sql.append(" AND (LOWER(hotel_name) LIKE ? OR LOWER(hotel_type) LIKE ? OR LOWER(city) LIKE ? OR LOWER(address) LIKE ?)");
                params.add("%" + singular + "%");
                params.add("%" + singular + "%");
                params.add("%" + singular + "%");
                params.add("%" + singular + "%");
            }
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                stmt.setString(i + 1, params.get(i));
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("hotel_id", rs.getString("hotel_id"));
                    item.put("hotel_name", rs.getString("hotel_name"));
                    item.put("hotel_type", rs.getString("hotel_type"));
                    item.put("city", rs.getString("city"));
                    item.put("state", rs.getString("state"));
                    item.put("room_price", rs.getObject("room_price"));
                    item.put("hotel_images", buildImageString(rs.getString("hotel_images")));
                    item.put("category", "hotel");
                    list.add(item);
                }
            }
        }
        return list;
    }

    private List<Map<String, Object>> getPGData(String searchQuery) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM paying_guest_info WHERE status = 'Active'");
        List<String> params = new ArrayList<>();

        if (searchQuery != null && !searchQuery.isBlank()) {
            String[] tokens = searchQuery.toLowerCase().split("\\s+");
            for (String token : tokens) {
                if (token.length() < 2) continue;
                String singular = normalizeString(token);
                sql.append(" AND (LOWER(pg_name) LIKE ? OR LOWER(pg_type) LIKE ? OR LOWER(city) LIKE ? OR LOWER(address) LIKE ?)");
                params.add("%" + singular + "%");
                params.add("%" + singular + "%");
                params.add("%" + singular + "%");
                params.add("%" + singular + "%");
            }
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            
            for (int i = 0; i < params.size(); i++) {
                stmt.setString(i + 1, params.get(i));
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getString("pg_id")); // Matched with CREATE TABLE schema
                    item.put("pg_name", rs.getString("pg_name"));
                    item.put("pg_type", rs.getString("pg_type"));
                    item.put("city", rs.getString("city"));
                    item.put("state", rs.getString("state"));
                    item.put("room_price", rs.getObject("room_price"));
                    item.put("pg_images", buildImageString(rs.getString("pg_images")));
                    item.put("category", "pg");
                    list.add(item);
                }
            }
        }
        return list;
    }

    private String buildImageString(String raw) {
        if (raw == null || raw.isBlank()) return "";
        List<String> processedList = new ArrayList<>();
        String[] parts = raw.split(",");
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (t.toLowerCase().startsWith("http")) {
                processedList.add(t);
            } else {
                String baseUrl = dbConfig.getImageBaseUrl();
                if (!baseUrl.endsWith("/")) baseUrl += "/";
                processedList.add(baseUrl + t.replaceAll("^/+", ""));
            }
        }
        return String.join(",", processedList);
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private void sendJsonResponse(HttpExchange exchange, int status, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }
}