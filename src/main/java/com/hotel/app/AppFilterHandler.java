package com.hotel.app;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;

public class AppFilterHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public AppFilterHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        
        String method = exchange.getRequestMethod();

        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if ("POST".equalsIgnoreCase(method) || "GET".equalsIgnoreCase(method)) {
            handleFilterRequest(exchange);
        } else {
            sendResponse(exchange, "Method Not Supported", 405);
        }
    }

    private void handleFilterRequest(HttpExchange exchange) throws IOException {
        try {
            JSONObject filters = new JSONObject();
            String sortBy = "none";

            // 1. Extract parameters from GET request
            String queryParams = exchange.getRequestURI().getRawQuery();
            if (queryParams != null) {
                for (String param : queryParams.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length > 1) {
                        filters.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8.toString()).trim());
                    }
                }
            }

            // 2. Extract parameters from POST request
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                StringBuilder requestBody = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        requestBody.append(line);
                    }
                }
                
                String bodyStr = requestBody.toString().trim();
                if (!bodyStr.isEmpty()) {
                    JSONObject requestJson = new JSONObject(bodyStr);
                    JSONObject bodyFilters = requestJson.has("filters") ? requestJson.getJSONObject("filters") : requestJson;
                    
                    for (String key : bodyFilters.keySet()) {
                        Object val = bodyFilters.get(key);
                        if (val instanceof String) {
                            filters.put(key, ((String) val).trim());
                        } else {
                            filters.put(key, val);
                        }
                    }
                    sortBy = requestJson.optString("sortBy", filters.optString("sortBy", "none"));
                }
            }

            JSONArray result = fetchDataWithFilters(filters, sortBy);
            sendJsonResponse(exchange, result.toString(), 200);

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, "Error: " + e.getMessage(), 500);
        }
    }

    private JSONArray fetchDataWithFilters(JSONObject filters, String sortBy) throws SQLException {
        JSONArray combinedResults = new JSONArray();

        // Standardize keys
        String hotelType = filters.optString("type", filters.optString("hotelType", "")).toLowerCase().trim();
        String searchQuery = filters.optString("searchQuery", filters.optString("query", "")).toLowerCase().trim();

        List<String> validHotelCategories = Arrays.asList("hotel", "resort", "lodge", "villa", "dormitory", "hotels", "resorts");
        List<String> validPgCategories = Arrays.asList("pg", "payingguest", "paying guest", "pgs");
        
        boolean searchHotels = true;
        boolean searchPGs = true;

        // Smart Category Detection - Check if user is searching for a specific type
        if (!hotelType.isEmpty() && !hotelType.equals("all")) {
            if (validPgCategories.contains(hotelType)) {
                searchHotels = false;
            } else if (validHotelCategories.contains(hotelType)) {
                searchPGs = false;
            }
        } else if (!searchQuery.isEmpty()) {
            // If the search query itself matches a category exactly (singular or plural)
            if (validHotelCategories.contains(searchQuery)) {
                searchPGs = false;
            } else if (validPgCategories.contains(searchQuery)) {
                searchHotels = false;
            }
        }

        if (searchHotels) {
            combinedResults.putAll(getFilteredData("hotels_info", "hotel_name", "hotel_type", filters, sortBy));
        }
        if (searchPGs) {
            combinedResults.putAll(getFilteredData("paying_guest_info", "pg_name", "pg_type", filters, sortBy));
        }

        return combinedResults;
    }

    private JSONArray getFilteredData(String tableName, String nameCol, String typeCol, JSONObject filters, String sortBy) throws SQLException {
        JSONArray dataArray = new JSONArray();
        List<Object> params = new ArrayList<>();
        
        StringBuilder query = new StringBuilder("SELECT * FROM " + tableName + " WHERE status = 'Active'");

        String searchQuery = filters.optString("searchQuery", filters.optString("query", "")).trim();
        String city = filters.optString("city", "").trim();

        if (!searchQuery.isEmpty()) {
            query.append(" AND (LOWER(").append(nameCol).append(") LIKE ? OR LOWER(").append(typeCol)
                 .append(") LIKE ? OR LOWER(city) LIKE ? OR LOWER(area) LIKE ?)");
            String p = "%" + searchQuery.toLowerCase() + "%";
            for (int i = 0; i < 4; i++) params.add(p);
        }

        if (!city.isEmpty()) {
            query.append(" AND (LOWER(city) = ? OR LOWER(state) = ?)");
            params.add(city.toLowerCase());
            params.add(city.toLowerCase());
        }

        double minPrice = filters.optDouble("minPrice", 0);
        double maxPrice = filters.optDouble("maxPrice", 0);
        String numericPriceSql = "CAST(REPLACE(REPLACE(room_price, '₹', ''), ',', '') AS DECIMAL(10,2))";

        if (minPrice > 0) {
            query.append(" AND ").append(numericPriceSql).append(" >= ?");
            params.add(minPrice);
        }
        if (maxPrice > 0) {
            query.append(" AND ").append(numericPriceSql).append(" <= ?");
            params.add(maxPrice);
        }

        if (filters.has("rating") && !filters.isNull("rating")) {
            double rating = filters.optDouble("rating", 0);
            if (rating > 0) {
                query.append(" AND rating >= ?");
                params.add(rating);
            }
        }

        if (!sortBy.isEmpty() && !sortBy.equals("none")) {
            switch (sortBy) {
                case "price_lowest": query.append(" ORDER BY ").append(numericPriceSql).append(" ASC"); break;
                case "price_highest": query.append(" ORDER BY ").append(numericPriceSql).append(" DESC"); break;
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
                    String col = meta.getColumnLabel(i).toLowerCase();
                    Object val = rs.getObject(i);
                    obj.put(col, val == null ? JSONObject.NULL : val);
                }
                
                if (tableName.equals("paying_guest_info")) {
                    obj.put("display_name", rs.getString("pg_name"));
                    obj.put("display_type", rs.getString("pg_type"));
                    obj.put("category_tag", "PG");
                } else {
                    obj.put("display_name", rs.getString("hotel_name"));
                    obj.put("display_type", rs.getString("hotel_type"));
                    obj.put("category_tag", "Hotel");
                }
                dataArray.put(obj);
            }
        }
        return dataArray;
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
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