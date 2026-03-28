package com.hotel.app;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class AppFilterHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public AppFilterHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Add CORS Headers to prevent blocking
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        try {
            JSONObject filters = new JSONObject();

            // FIXED: Logic to read from URL (GET) or Body (POST)
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getRawQuery();
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] pair = param.split("=", 2);
                        if (pair.length == 2) {
                            filters.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), 
                                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                        }
                    }
                }
            } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                StringBuilder body = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) body.append(line);
                }
                if (body.length() > 0) {
                    JSONObject requestJson = new JSONObject(body.toString());
                    filters = requestJson.has("filters") ? requestJson.getJSONObject("filters") : requestJson;
                }
            }

            String sortBy = filters.optString("sortBy", "none");
            JSONArray result = fetchDataWithFilters(filters, sortBy);
            sendJsonResponse(exchange, result.toString(), 200);

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, "Error processing filters: " + e.getMessage(), 500);
        }
    }

    private JSONArray fetchDataWithFilters(JSONObject filters, String sortBy) throws SQLException {
        JSONArray combined = new JSONArray();
        // Schema fix: using 'avg_rating' instead of 'rating'
        combined.putAll(getFilteredData("hotels_info", "Hotel_Name", "Hotel_Type", "Room_Price", "avg_rating", filters, sortBy));
        combined.putAll(getFilteredData("paying_guest_info", "pg_name", "pg_type", "room_price", "avg_rating", filters, sortBy));
        return combined;
    }

    private JSONArray getFilteredData(String table, String nameCol, String typeCol, String priceCol, String ratingCol, JSONObject filters, String sortBy) throws SQLException {
        JSONArray array = new JSONArray();
        List<Object> params = new ArrayList<>();
        StringBuilder query = new StringBuilder("SELECT * FROM " + table + " WHERE status='Active'");

        // 1. SMART SEARCH (Type, Name, or Query)
        String searchQuery = filters.optString("query", filters.optString("searchQuery", filters.optString("type", ""))).trim();
        if (!searchQuery.isEmpty() && !searchQuery.equalsIgnoreCase("All")) {
            String sanitized = searchQuery.toLowerCase();
            if (sanitized.endsWith("s") && sanitized.length() > 3) sanitized = sanitized.substring(0, sanitized.length() - 1);
            
            query.append(" AND (LOWER(").append(nameCol).append(") LIKE ? OR LOWER(").append(typeCol).append(") LIKE ? OR LOWER(city) LIKE ?)");
            String p = "%" + sanitized + "%";
            params.add(p); params.add(p); params.add(p);
        }

        // 2. CITY
        String city = filters.optString("city", "").trim();
        if (!city.isEmpty() && !city.equalsIgnoreCase("Manual Location")) {
            query.append(" AND LOWER(city) = ?");
            params.add(city.toLowerCase());
        }

        // 3. RATING
        double rating = filters.optDouble("rating", 0.0);
        if (rating > 0) {
            query.append(" AND ").append(ratingCol).append(" >= ?");
            params.add(rating);
        }

        // 4. PRICE (Fuzzy range check for CSV prices)
        double minPrice = filters.optDouble("minPrice", 0.0);
        double maxPrice = filters.optDouble("maxPrice", 0.0);
        if (maxPrice > 0) {
            query.append(" AND CAST(SUBSTRING_INDEX(").append(priceCol).append(", ',', 1) AS DECIMAL) BETWEEN ? AND ?");
            params.add(minPrice);
            params.add(maxPrice);
        }

        // 5. SORTING
        if (!sortBy.equals("none")) {
            switch (sortBy) {
                case "price_lowest": query.append(" ORDER BY CAST(SUBSTRING_INDEX(").append(priceCol).append(", ',', 1) AS DECIMAL) ASC"); break;
                case "price_highest": query.append(" ORDER BY CAST(SUBSTRING_INDEX(").append(priceCol).append(", ',', 1) AS DECIMAL) DESC"); break;
                case "top_rated": query.append(" ORDER BY ").append(ratingCol).append(" DESC"); break;
            }
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(query.toString())) {
            for (int i = 0; i < params.size(); i++) stmt.setObject(i + 1, params.get(i));
            ResultSet rs = stmt.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                JSONObject obj = new JSONObject();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String col = meta.getColumnLabel(i).toLowerCase();
                    Object val = rs.getObject(i);
                    // Proccess images to be full URLs
                    if (col.contains("images") && val != null) val = buildImageUrl(val.toString());
                    obj.put(col, val == null ? JSONObject.NULL : val);
                }
                array.put(obj);
            }
        }
        return array;
    }

    private String buildImageUrl(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String first = raw.split(",")[0].trim();
        if (first.startsWith("http")) return first;
        String base = dbConfig.getImageBaseUrl();
        return (base.endsWith("/") ? base : base + "/") + first.replaceAll("^/+", "");
    }

    private void sendJsonResponse(HttpExchange exchange, String response, int statusCode) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void sendResponse(HttpExchange exchange, String response, int statusCode) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }
}