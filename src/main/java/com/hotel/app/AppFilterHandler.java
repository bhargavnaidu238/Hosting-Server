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
        // Support both GET (standard for search) and POST (for legacy support)
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

            // CASE 1: Handle GET Query Parameters
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                filters = parseQueryString(query);
                sortBy = filters.optString("sortBy", "none");
            } 
            // CASE 2: Handle POST JSON Body
            else {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), "utf-8"))) {
                    StringBuilder body = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) body.append(line);
                    
                    if (body.length() > 0) {
                        JSONObject requestJson = new JSONObject(body.toString());
                        filters = requestJson.has("filters") ? requestJson.getJSONObject("filters") : requestJson;
                        sortBy = requestJson.optString("sortBy", filters.optString("sortBy", "none"));
                    }
                }
            }

            JSONArray result = fetchDataWithFilters(filters, sortBy);
            sendJsonResponse(exchange, result.toString(), 200);

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, "Internal Server Error: " + e.getMessage(), 500);
        }
    }

    private JSONArray fetchDataWithFilters(JSONObject filters, String sortBy) throws SQLException {
        JSONArray combined = new JSONArray();
        
        // Use "query" or "search" or "searchQuery" to be flexible with frontend names
        String search = filters.optString("query", filters.optString("search", filters.optString("searchQuery", "")));
        filters.put("searchQuery", search);

        // Fetch from Hotels table
        combined.putAll(getFilteredData("hotels_info", "Hotel_Name", "Hotel_Type", "Room_Price", filters, sortBy));
        
        // Only fetch PGs if no specific Hotel Type is requested, or if Type is 'PG'
        String requestedType = filters.optString("type", "All");
        if (requestedType.equalsIgnoreCase("All") || requestedType.contains("PG")) {
            combined.putAll(getFilteredData("paying_guest_info", "pg_name", "pg_type", "room_price", filters, sortBy));
        }

        return combined;
    }

    private JSONArray getFilteredData(String table, String nameCol, String typeCol, String priceCol, JSONObject filters, String sortBy) throws SQLException {
        JSONArray array = new JSONArray();
        List<Object> params = new ArrayList<>();
        StringBuilder query = new StringBuilder("SELECT * FROM " + table + " WHERE status='Active'");

        // 1. TYPE FILTER
        String type = filters.optString("type", "All");
        if (!type.equalsIgnoreCase("All")) {
            query.append(" AND (LOWER(").append(typeCol).append(") = ?)");
            params.add(type.toLowerCase());
        }

        // 2. SEARCH LOGIC
        String search = filters.optString("searchQuery", "").trim();
        if (!search.isEmpty()) {
            query.append(" AND (LOWER(").append(nameCol).append(") LIKE ? OR LOWER(city) LIKE ? OR LOWER(address) LIKE ?)");
            String pattern = "%" + search.toLowerCase() + "%";
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }

        // 3. CITY FILTER (Explicit)
        String city = filters.optString("city", "").trim();
        if (!city.isEmpty()) {
            query.append(" AND LOWER(city) = ?");
            params.add(city.toLowerCase());
        }

        // 4. RATING FILTER
        double rating = filters.optDouble("rating", 0);
        if (rating > 0) {
            query.append(" AND rating >= ?");
            params.add(rating);
        }

        // 5. PRICE FILTER
        double minPrice = filters.optDouble("minPrice", 0);
        double maxPrice = filters.optDouble("maxPrice", 0);
        if (maxPrice > 0) {
            // Logic to parse the first price in a comma-separated list
            query.append(" AND CAST(SUBSTRING_INDEX(").append(priceCol).append(", ',', 1) AS DECIMAL) BETWEEN ? AND ?");
            params.add(minPrice);
            params.add(maxPrice);
        }

        // 6. SORTING
        if (sortBy != null && !sortBy.equals("none")) {
            switch (sortBy) {
                case "price_lowest": query.append(" ORDER BY CAST(SUBSTRING_INDEX(").append(priceCol).append(", ',', 1) AS DECIMAL) ASC"); break;
                case "price_highest": query.append(" ORDER BY CAST(SUBSTRING_INDEX(").append(priceCol).append(", ',', 1) AS DECIMAL) DESC"); break;
                case "top_rated": query.append(" ORDER BY rating DESC"); break;
            }
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(query.toString())) {

            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            ResultSet rs = stmt.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();

            while (rs.next()) {
                JSONObject obj = new JSONObject();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String col = meta.getColumnLabel(i);
                    Object val = rs.getObject(i);
                    obj.put(col, val == null ? JSONObject.NULL : val);
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
                if (kv.length > 1) {
                    json.put(kv[0], URLDecoder.decode(kv[1], "UTF-8"));
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return json;
    }

    private void sendJsonResponse(HttpExchange exchange, String response, int statusCode) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        byte[] bytes = response.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void sendResponse(HttpExchange exchange, String response, int statusCode) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        byte[] bytes = response.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }
}