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

public class AppFilterHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public AppFilterHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            handleFilterRequest(exchange);
        } else {
            sendResponse(exchange, "Only POST method is supported", 405);
        }
    }

    private void handleFilterRequest(HttpExchange exchange) throws IOException {
        try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(isr)) {

            StringBuilder requestBody = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                requestBody.append(line);
            }

            String bodyStr = requestBody.toString().trim();
            if (bodyStr.isEmpty()) {
                sendJsonResponse(exchange, new JSONArray().toString(), 200);
                return;
            }

            JSONObject requestJson = new JSONObject(bodyStr);
            // Support both flat and nested filter structures
            JSONObject filters = requestJson.has("filters") ? requestJson.getJSONObject("filters") : requestJson;
            String sortBy = requestJson.optString("sortBy", filters.optString("sortBy", ""));

            JSONArray result = fetchHotelsWithFilters(filters, sortBy);
            sendJsonResponse(exchange, result.toString(), 200);

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, "Error: " + e.getMessage(), 500);
        }
    }

    private JSONArray fetchHotelsWithFilters(JSONObject filters, String sortBy) throws SQLException {
        JSONArray hotelsArray = new JSONArray();
        StringBuilder baseQuery = new StringBuilder("SELECT * FROM hotels_info WHERE Status = 'Active'");
        List<Object> params = new ArrayList<>();

        // 1. Inputs
        String hotelType = filters.optString("hotelType", filters.optString("hoteltype", "")).trim();
        String searchQuery = filters.optString("searchQuery", "").trim();

        // 2. Strict Category Normalization (The Fix)
        // We only strip 's' if it matches a known category. 
        // This prevents "Marriotts" from becoming "Marriott" incorrectly if it's a name search.
        List<String> validCategories = Arrays.asList("hotel", "resort", "lodge", "pg", "payingguest");
        
        if (!searchQuery.isEmpty() && hotelType.isEmpty()) {
            String input = searchQuery.toLowerCase();
            String singularInput = (input.endsWith("s") && input.length() > 3) 
                                   ? input.substring(0, input.length() - 1) 
                                   : input;

            if (validCategories.contains(singularInput)) {
                hotelType = singularInput;
                searchQuery = ""; // Effectively shifts query to category filter
            }
        }

        // Apply Category Filter (Normalized)
        if (!hotelType.isEmpty()) {
            String cleanType = hotelType.toLowerCase();
            if (cleanType.endsWith("s") && validCategories.contains(cleanType.substring(0, cleanType.length() -1))) {
                cleanType = cleanType.substring(0, cleanType.length() - 1);
            }
            baseQuery.append(" AND LOWER(Hotel_Type) = ?");
            params.add(cleanType);
        }

        // 3. Keyword Search (Hotel Name, City, or State)
        if (!searchQuery.isEmpty()) {
            String query = "%" + searchQuery.toLowerCase() + "%";
            baseQuery.append(" AND (LOWER(Hotel_Name) LIKE ? OR LOWER(city) LIKE ? OR LOWER(state) LIKE ?)");
            params.add(query); params.add(query); params.add(query);
        }

        // 4. Explicit City Filter
        String city = filters.optString("city", "").trim();
        if (!city.isEmpty()) {
            baseQuery.append(" AND LOWER(city) = ?");
            params.add(city.toLowerCase());
        }

        // 5. Sorting Logic
        String orderClause = "";
        if (!sortBy.isEmpty() && !sortBy.equals("none")) {
            switch (sortBy) {
                case "price_lowest":
                    orderClause = " ORDER BY CAST(REPLACE(REPLACE(Room_Price,'₹',''),',','') AS DECIMAL(10,2)) ASC";
                    break;
                case "price_highest":
                    orderClause = " ORDER BY CAST(REPLACE(REPLACE(Room_Price,'₹',''),',','') AS DECIMAL(10,2)) DESC";
                    break;
                case "top_rated":
                    orderClause = " ORDER BY Rating DESC";
                    break;
            }
        }

        String finalQuery = baseQuery.toString() + orderClause + " LIMIT 100";

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(finalQuery)) {

            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            ResultSet rs = stmt.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                JSONObject hotel = new JSONObject();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String col = meta.getColumnLabel(i);
                    Object val = rs.getObject(col);
                    hotel.put(col, val == null ? JSONObject.NULL : val);
                }
                hotelsArray.put(hotel);
            }
        }
        return hotelsArray;
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private void sendJsonResponse(HttpExchange exchange, String response, int statusCode) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendResponse(HttpExchange exchange, String response, int statusCode) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}