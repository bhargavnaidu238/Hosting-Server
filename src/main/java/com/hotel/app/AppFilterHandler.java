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
            JSONObject filters = requestJson.has("filters") ? requestJson.getJSONObject("filters") : requestJson;
            String sortBy = requestJson.optString("sortBy", filters.optString("sortBy", ""));

            // FETCH LOGIC
            JSONArray result = fetchDataWithFilters(filters, sortBy);
            sendJsonResponse(exchange, result.toString(), 200);

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, "Error: " + e.getMessage(), 500);
        }
    }

    private JSONArray fetchDataWithFilters(JSONObject filters, String sortBy) throws SQLException {
        JSONArray combinedResults = new JSONArray();

        String hotelType = filters.optString("hotelType", filters.optString("hoteltype", "")).trim();
        String searchQuery = filters.optString("searchQuery", "").trim();
        String city = filters.optString("city", "").trim();

        // Strict Category Normalization
        List<String> validHotelCategories = Arrays.asList("hotel", "resort", "lodge", "villa", "dormitory");
        List<String> validPgCategories = Arrays.asList("pg", "payingguest");
        
        String input = searchQuery.toLowerCase();
        String singularInput = (input.endsWith("s") && input.length() > 3) ? input.substring(0, input.length() - 1) : input;

        // Routing logic based on search text or explicit type
        boolean searchHotels = true;
        boolean searchPGs = true;

        if (validHotelCategories.contains(singularInput) || validHotelCategories.contains(hotelType.toLowerCase())) {
            searchPGs = false;
        } else if (validPgCategories.contains(singularInput) || validPgCategories.contains(hotelType.toLowerCase())) {
            searchHotels = false;
        }

        // 1. Fetch Hotels
        if (searchHotels) {
            combinedResults.putAll(getFilteredData("hotels_info", "Hotel_Name", "Hotel_Type", filters, sortBy));
        }

        // 2. Fetch PGs
        if (searchPGs) {
            combinedResults.putAll(getFilteredData("paying_guest_info", "pg_name", "pg_type", filters, sortBy));
        }

        return combinedResults;
    }

    private JSONArray getFilteredData(String tableName, String nameCol, String typeCol, JSONObject filters, String sortBy) throws SQLException {
        JSONArray dataArray = new JSONArray();
        List<Object> params = new ArrayList<>();
        
        StringBuilder query = new StringBuilder("SELECT * FROM " + tableName + " WHERE Status = 'Active'");

        String hotelType = filters.optString("hotelType", filters.optString("hoteltype", "")).trim();
        String searchQuery = filters.optString("searchQuery", "").trim();
        String city = filters.optString("city", "").trim();

        // Type Filter
        if (!hotelType.isEmpty() && !hotelType.equalsIgnoreCase("all")) {
            query.append(" AND LOWER(" + typeCol + ") = ?");
            params.add(hotelType.toLowerCase());
        }

        // Keyword Search (Name, Type, City, State, Country)
        if (!searchQuery.isEmpty()) {
            String p = "%" + searchQuery.toLowerCase() + "%";
            query.append(" AND (LOWER(" + nameCol + ") LIKE ? OR LOWER(" + typeCol + ") LIKE ? OR LOWER(city) LIKE ? OR LOWER(state) LIKE ? OR LOWER(country) LIKE ?)");
            for (int i = 0; i < 5; i++) params.add(p);
        }

        // Explicit City Filter
        if (!city.isEmpty()) {
            query.append(" AND LOWER(city) = ?");
            params.add(city.toLowerCase());
        }

        // Sorting
        if (!sortBy.isEmpty() && !sortBy.equals("none")) {
            switch (sortBy) {
                case "price_lowest":
                    query.append(" ORDER BY CAST(REPLACE(REPLACE(Room_Price,'₹',''),',','') AS DECIMAL(10,2)) ASC");
                    break;
                case "price_highest":
                    query.append(" ORDER BY CAST(REPLACE(REPLACE(Room_Price,'₹',''),',','') AS DECIMAL(10,2)) DESC");
                    break;
                case "top_rated":
                    query.append(" ORDER BY Rating DESC");
                    break;
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
                    obj.put(col.toLowerCase(), rs.getObject(col));
                }
                obj.put("category_type", tableName.contains("hotel") ? "hotel" : "pg");
                dataArray.put(obj);
            }
        }
        return dataArray;
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
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