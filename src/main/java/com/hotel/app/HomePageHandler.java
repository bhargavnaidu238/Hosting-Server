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
                } else if ("query".equalsIgnoreCase(key) || "q".equalsIgnoreCase(key) || "searchQuery".equalsIgnoreCase(key)) {
                    searchQuery = value.trim();
                }
            }
        }

        String normalizedType = (hotelType == null) ? "" : hotelType.replaceAll("[_\\-\\s]", "").toLowerCase();

        // --- IMPROVED ROUTING LOGIC ---
        try {
            List<Map<String, Object>> results = new ArrayList<>();

            // 1. If user typed in search bar AND no specific category was clicked (Landing on page via search)
            if (searchQuery != null && !searchQuery.isBlank() && (hotelType == null || hotelType.isBlank() || hotelType.equalsIgnoreCase("all"))) {
                results.addAll(getHotelsData(null, searchQuery));
                results.addAll(getPGData(searchQuery));
            } 
            // 2. If user specifically clicked "Paying Guests" category
            else if (normalizedType.equals("payingguest") || normalizedType.equals("pg")) {
                results.addAll(getPGData(searchQuery));
            } 
            // 3. If user clicked a specific Hotel category (Hotels, Resorts, etc.) or just "all"
            else {
                results.addAll(getHotelsData(hotelType, searchQuery));
            }

            sendJsonResponse(exchange, 200, objectMapper.writeValueAsString(results));

        } catch (SQLException e) {
            e.printStackTrace();
            sendJsonResponse(exchange, 500, "{\"error\":\"Database error: " + e.getMessage() + "\"}");
        }
    }

    private void handleGlobalSearch(HttpExchange exchange, String searchQuery) throws IOException {
        // This method is now integrated into handle() to prevent Unhandled SQLException errors
    }

    private void handleHotelRequest(HttpExchange exchange, String hotelType, String searchQuery) throws IOException {
        // This method is now integrated into handle() to prevent Unhandled SQLException errors
    }

    private void handlePayingGuestRequest(HttpExchange exchange, String searchQuery) throws IOException {
        // This method is now integrated into handle() to prevent Unhandled SQLException errors
    }

    private List<Map<String, Object>> getHotelsData(String hotelType, String searchQuery) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM hotels_info WHERE status = 'Active'");

        // Apply type filter if specifically requested (e.g. from a category card)
        if (hotelType != null && !hotelType.isBlank() && !hotelType.equalsIgnoreCase("all")) {
            sql.append(" AND LOWER(hotel_type) = ?");
        }
        
        // Apply keyword search across all required columns
        if (searchQuery != null && !searchQuery.isBlank()) {
            sql.append(" AND (LOWER(hotel_name) LIKE ? OR LOWER(hotel_type) LIKE ? OR LOWER(city) LIKE ? OR LOWER(state) LIKE ? OR LOWER(country) LIKE ?)");
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            int idx = 1;
            if (hotelType != null && !hotelType.isBlank() && !hotelType.equalsIgnoreCase("all")) {
                stmt.setString(idx++, hotelType.toLowerCase());
            }
            if (searchQuery != null && !searchQuery.isBlank()) {
                String p = "%" + searchQuery.toLowerCase() + "%";
                for (int i = 0; i < 5; i++) stmt.setString(idx++, p);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("hotel_id", rs.getString("hotel_id"));
                    item.put("hotel_name", rs.getString("hotel_name"));
                    item.put("hotel_type", rs.getString("hotel_type"));
                    item.put("city", rs.getString("city"));
                    item.put("state", rs.getString("state"));
                    item.put("country", rs.getString("country"));
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

        // Search across all required columns for PG table
        if (searchQuery != null && !searchQuery.isBlank()) {
            sql.append(" AND (LOWER(pg_name) LIKE ? OR LOWER(pg_type) LIKE ? OR LOWER(city) LIKE ? OR LOWER(state) LIKE ? OR LOWER(country) LIKE ?)");
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            
            if (searchQuery != null && !searchQuery.isBlank()) {
                String p = "%" + searchQuery.toLowerCase() + "%";
                for (int i = 1; i <= 5; i++) stmt.setString(i, p);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("pg_name", rs.getString("pg_name"));
                    item.put("pg_type", rs.getString("pg_type"));
                    item.put("city", rs.getString("city"));
                    item.put("state", rs.getString("state"));
                    item.put("country", rs.getString("country"));
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