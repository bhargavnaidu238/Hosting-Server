package com.hotel.app;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.sql.*;
import java.util.*;

public class AppFilterHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public AppFilterHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            handleFilterRequest(exchange);
        } else {
            sendResponse(exchange, "Only POST method supported", 405);
        }
    }

    private void handleFilterRequest(HttpExchange exchange) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), "utf-8"))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                body.append(line);
            }

            if (body.toString().trim().isEmpty()) {
                sendJsonResponse(exchange, new JSONArray().toString(), 200);
                return;
            }

            JSONObject requestJson = new JSONObject(body.toString());
            // Support both direct filters or nested filters object
            JSONObject filters = requestJson.has("filters") ? requestJson.getJSONObject("filters") : requestJson;
            String sortBy = requestJson.optString("sortBy", filters.optString("sortBy", "none"));

            JSONArray result = fetchDataWithFilters(filters, sortBy);
            sendJsonResponse(exchange, result.toString(), 200);

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, "Internal Server Error: " + e.getMessage(), 500);
        }
    }

    private JSONArray fetchDataWithFilters(JSONObject filters, String sortBy) throws SQLException {
        JSONArray combined = new JSONArray();
        
        // Fetch from Hotels
        combined.putAll(getFilteredData("hotels_info", "Hotel_Name", "Hotel_Type", "Room_Price", filters, sortBy));
        
        // Fetch from PGs
        combined.putAll(getFilteredData("paying_guest_info", "pg_name", "pg_type", "room_price", filters, sortBy));

        return combined;
    }

    private JSONArray getFilteredData(String table, String nameCol, String typeCol, String priceCol, JSONObject filters, String sortBy) throws SQLException {
        JSONArray array = new JSONArray();
        List<Object> params = new ArrayList<>();
        StringBuilder query = new StringBuilder("SELECT * FROM " + table + " WHERE status='Active'");

        // 1. IMPROVED SEARCH LOGIC (Fixes the "Vivanta" issue)
        String searchQuery = filters.optString("searchQuery", "").trim();
        if (!searchQuery.isEmpty()) {
            // Priority: Name match or location match
            query.append(" AND (LOWER(").append(nameCol).append(") LIKE ? OR LOWER(city) LIKE ?)");
            params.add("%" + searchQuery.toLowerCase() + "%");
            params.add("%" + searchQuery.toLowerCase() + "%");
        }

        // 2. CITY FILTER
        String city = filters.optString("city", "").trim();
        if (!city.isEmpty()) {
            query.append(" AND (LOWER(city) = ?)");
            params.add(city.toLowerCase());
        }

        // 3. RATING FILTER
        double rating = filters.optDouble("rating", 0);
        if (rating > 0) {
            query.append(" AND rating >= ?");
            params.add(rating);
        }

        // 4. PRICE FILTER (Handling comma-separated strings)
        double minPrice = filters.optDouble("minPrice", 0);
        double maxPrice = filters.optDouble("maxPrice", 0);
        if (maxPrice > 0) {
            query.append(" AND (");
            // This checks the 1st, 2nd, and 3rd price in your comma list
            query.append("CAST(SUBSTRING_INDEX(").append(priceCol).append(", ',', 1) AS DECIMAL) BETWEEN ? AND ? ");
            query.append("OR CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(").append(priceCol).append(", ',', 2), ',', -1) AS DECIMAL) BETWEEN ? AND ? ");
            query.append("OR CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(").append(priceCol).append(", ',', 3), ',', -1) AS DECIMAL) BETWEEN ? AND ?");
            query.append(")");

            // Add 6 params (min, max, min, max, min, max)
            for(int i=0; i<3; i++) {
                params.add(minPrice);
                params.add(maxPrice);
            }
        }

        // 5. SORTING LOGIC
        if (sortBy != null && !sortBy.equals("none")) {
            String firstPriceExpr = "CAST(SUBSTRING_INDEX(" + priceCol + ", ',', 1) AS DECIMAL)";
            switch (sortBy) {
                case "price_lowest": query.append(" ORDER BY ").append(firstPriceExpr).append(" ASC"); break;
                case "price_highest": query.append(" ORDER BY ").append(firstPriceExpr).append(" DESC"); break;
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
                    obj.put(col.toLowerCase(), val == null ? JSONObject.NULL : val);
                }
                
                // Unified Display Fields
                boolean isPg = table.equalsIgnoreCase("paying_guest_info");
                obj.put("display_name", rs.getString(isPg ? "pg_name" : "Hotel_Name"));
                obj.put("display_type", rs.getString(isPg ? "pg_type" : "Hotel_Type"));
                obj.put("category_tag", isPg ? "PG" : "Hotel");
                
                array.put(obj);
            }
        }
        return array;
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