package com.hotel.app;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.URLDecoder;
import java.sql.*;
import java.util.*;

public class AppFilterHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public AppFilterHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) || "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            handleFilterRequest(exchange);
        } else {
            sendResponse(exchange, "Method not supported", 405);
        }
    }

    private void handleFilterRequest(HttpExchange exchange) throws IOException {
        try {
            JSONObject filters = new JSONObject();
            String sortBy = "none";

            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                filters = parseQueryString(query);
                sortBy = filters.optString("sortBy", "none");
            } else {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), "utf-8"))) {
                    StringBuilder body = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) body.append(line);
                    if (body.length() > 0) {
                        JSONObject requestJson = new JSONObject(body.toString());
                        filters = requestJson.has("filters") ? requestJson.getJSONObject("filters") : requestJson;
                        sortBy = requestJson.optString("sortBy", "none");
                    }
                }
            }

            JSONArray result = fetchDataWithFilters(filters, sortBy);
            sendJsonResponse(exchange, result.toString(), 200);

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, "Internal Server Error", 500);
        }
    }

    private JSONArray fetchDataWithFilters(JSONObject filters, String sortBy) throws SQLException {
        JSONArray combined = new JSONArray();
        String search = filters.optString("query", filters.optString("search", filters.optString("searchQuery", "")));
        filters.put("searchQuery", search);

        // Fetch Hotels
        combined.putAll(getFilteredData("hotels_info", "hotel_name", "hotel_type", "room_price", filters, sortBy));
        
        // Fetch PGs if applicable
        String type = filters.optString("type", "All");
        if (type.equalsIgnoreCase("All") || type.toLowerCase().contains("pg")) {
            combined.putAll(getFilteredData("paying_guest_info", "pg_name", "pg_type", "room_price", filters, sortBy));
        }

        return combined;
    }

    private JSONArray getFilteredData(String table, String nameCol, String typeCol, String priceCol, JSONObject filters, String sortBy) throws SQLException {
        JSONArray array = new JSONArray();
        List<Object> params = new ArrayList<>();
        
        // NEW: Location Parameters
        double uLat = filters.optDouble("lat", 0);
        double uLng = filters.optDouble("lng", 0);
        double radius = filters.optDouble("radius", 0); // 20km from frontend
        boolean nearbySearch = (uLat != 0 && uLng != 0 && radius > 0);

        StringBuilder query = new StringBuilder("SELECT *");
        
        // Haversine formula calculation for PostgreSQL
        if (nearbySearch) {
            query.append(", (6371 * acos(cos(radians(?)) * cos(radians(latitude)) * cos(radians(longitude) - radians(?)) + sin(radians(?)) * sin(radians(latitude)))) AS distance");
        }
        
        query.append(" FROM ").append(table).append(" WHERE status='Active'");

        if (nearbySearch) {
            params.add(uLat);
            params.add(uLng);
            params.add(uLat);
        }

        // 1. SEARCH LOGIC
        String search = filters.optString("searchQuery", "").trim();
        if (!search.isEmpty()) {
            query.append(" AND (LOWER(").append(nameCol).append(") LIKE ? OR LOWER(city) LIKE ? OR LOWER(address) LIKE ?)");
            String pattern = "%" + search.toLowerCase() + "%";
            params.add(pattern); params.add(pattern); params.add(pattern);
        }

        // 2. RADIUS CONSTRAINT (20km)
        if (nearbySearch) {
            query.append(" AND (6371 * acos(cos(radians(?)) * cos(radians(latitude)) * cos(radians(longitude) - radians(?)) + sin(radians(?)) * sin(radians(latitude)))) <= ?");
            params.add(uLat);
            params.add(uLng);
            params.add(uLat);
            params.add(radius);
        }

        // 3. RATING & PRICE
        double rating = filters.optDouble("rating", 0);
        if (rating > 0) { query.append(" AND avg_rating >= ?"); params.add(rating); }

        double maxPrice = filters.optDouble("maxPrice", 0);
        if (maxPrice > 0) {
            double minPrice = filters.optDouble("minPrice", 0);
            query.append(" AND CAST(split_part(").append(priceCol).append(", ',', 1) AS DECIMAL) BETWEEN ? AND ?");
            params.add(minPrice); params.add(maxPrice);
        }

        // 4. SORTING
        if (nearbySearch && sortBy.equals("none")) {
            query.append(" ORDER BY distance ASC");
        } else if (!sortBy.equals("none")) {
            switch (sortBy) {
                case "price_lowest": query.append(" ORDER BY CAST(split_part(").append(priceCol).append(", ',', 1) AS DECIMAL) ASC"); break;
                case "price_highest": query.append(" ORDER BY CAST(split_part(").append(priceCol).append(", ',', 1) AS DECIMAL) DESC"); break;
                case "top_rated": query.append(" ORDER BY avg_rating DESC"); break;
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
                    String col = meta.getColumnLabel(i);
                    obj.put(col, rs.getObject(i) == null ? JSONObject.NULL : rs.getObject(i));
                }
                array.put(obj);
            }
        }
        return array;
    }

    private JSONObject parseQueryString(String query) {
        JSONObject json = new JSONObject();
        if (query == null || query.isEmpty()) return json;
        try {
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=");
                if (kv.length > 1) json.put(kv[0], URLDecoder.decode(kv[1], "UTF-8"));
            }
        } catch (Exception e) {}
        return json;
    }

    private void sendJsonResponse(HttpExchange exchange, String response, int statusCode) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = response.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void sendResponse(HttpExchange exchange, String response, int statusCode) throws IOException {
        byte[] bytes = response.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }
}