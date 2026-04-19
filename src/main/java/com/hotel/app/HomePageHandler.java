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
        Map<String, String> params = parseQueryParameters(requestURI.getQuery());

        String hotelType = params.get("type");
        String searchQuery = params.get("query");
        
        // Location Support for "Nearby" Home Suggestions
        double lat = 0.0;
        double lng = 0.0;
        try {
            if (params.containsKey("lat")) lat = Double.parseDouble(params.get("lat"));
            if (params.containsKey("lng")) lng = Double.parseDouble(params.get("lng"));
        } catch (Exception e) { /* default to 0 */ }

        String normalizedType = normalizeString(hotelType);

        // ROUTING BASED ON YOUR DART CATEGORIES
        if (normalizedType.contains("payingguest") || normalizedType.contains("pg")) {
            handlePayingGuestRequest(exchange, searchQuery, lat, lng);
        } else if (searchQuery != null && !searchQuery.isBlank() && normalizedType.isEmpty()) {
            handleGlobalSearch(exchange, searchQuery, lat, lng);
        } else {
            handleHotelRequest(exchange, hotelType, searchQuery, lat, lng);
        }
    }

    private void handleGlobalSearch(HttpExchange exchange, String query, double lat, double lng) throws IOException {
        try {
            List<Map<String, Object>> results = new ArrayList<>();
            results.addAll(getFilteredData("hotels_info", null, query, lat, lng));
            results.addAll(getFilteredData("paying_guest_info", null, query, lat, lng));
            sendJsonResponse(exchange, 200, objectMapper.writeValueAsString(results));
        } catch (SQLException e) {
            sendJsonResponse(exchange, 500, "{\"error\":\"Global search failed\"}");
        }
    }

    private void handleHotelRequest(HttpExchange exchange, String type, String query, double lat, double lng) throws IOException {
        try {
            List<Map<String, Object>> hotels = getFilteredData("hotels_info", type, query, lat, lng);
            sendJsonResponse(exchange, 200, objectMapper.writeValueAsString(hotels));
        } catch (SQLException e) {
            sendJsonResponse(exchange, 500, "{\"error\":\"Hotel fetch failed\"}");
        }
    }

    private void handlePayingGuestRequest(HttpExchange exchange, String query, double lat, double lng) throws IOException {
        try {
            List<Map<String, Object>> pgs = getFilteredData("paying_guest_info", null, query, lat, lng);
            sendJsonResponse(exchange, 200, objectMapper.writeValueAsString(pgs));
        } catch (SQLException e) {
            sendJsonResponse(exchange, 500, "{\"error\":\"PG fetch failed\"}");
        }
    }

    private List<Map<String, Object>> getFilteredData(String table, String type, String query, double uLat, double uLng) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        boolean isNearby = (uLat != 0 && uLng != 0);

        StringBuilder sql = new StringBuilder("SELECT *");
        if (isNearby) {
            // Haversine formula for sorting by proximity
            sql.append(", (6371 * acos(cos(radians(?)) * cos(radians(latitude)) * cos(radians(longitude) - radians(?)) + sin(radians(?)) * sin(radians(latitude)))) AS distance");
            params.add(uLat); params.add(uLng); params.add(uLat);
        }
        
        sql.append(" FROM ").append(table).append(" WHERE status = 'Active'");

        // 1. CATEGORY FILTER (Strict)
        if (type != null && !type.isBlank()) {
            String col = table.equals("hotels_info") ? "hotel_type" : "pg_type";
            sql.append(" AND LOWER(").append(col).append(") = ?");
            params.add(type.toLowerCase().trim());
        }

        // 2. SEARCH QUERY (Fuzzy)
        if (query != null && !query.isBlank()) {
            String nameCol = table.equals("hotels_info") ? "hotel_name" : "pg_name";
            sql.append(" AND (LOWER(").append(nameCol).append(") LIKE ? OR LOWER(city) LIKE ?)");
            String pattern = "%" + query.toLowerCase() + "%";
            params.add(pattern); params.add(pattern);
        }

        if (isNearby) {
            sql.append(" ORDER BY distance ASC");
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) stmt.setObject(i + 1, params.get(i));

            try (ResultSet rs = stmt.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        String colLabel = meta.getColumnLabel(i).toLowerCase();
                        item.put(colLabel, rs.getObject(i));
                    }
                    list.add(item);
                }
            }
        }
        return list;
    }

    private Map<String, String> parseQueryParameters(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null) return params;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length > 0) {
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
                params.put(key, value);
            }
        }
        return params;
    }

    private String normalizeString(String input) {
        if (input == null) return "";
        return input.replaceAll("[_\\-\\s]", "").toLowerCase().replace("s", "");
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