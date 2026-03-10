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

    // ✅ Inject DbConfig via constructor
    public AppFilterHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            handleFilterRequest(exchange);
        } else {
            sendResponse(exchange, "Only POST method is supported", 405);
        }
    }

    private void handleFilterRequest(HttpExchange exchange) throws IOException {
        try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), "utf-8");
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

            // New frontend sends: { "type": "...", "filters": {...}, "sortBy": "price_lowest" }
            JSONObject filters;
            String sortBy = "";

            if (requestJson.has("filters")) {
                filters = requestJson.getJSONObject("filters");
            } else {
                // Backward-compat: the client may have sent filters at top-level
                filters = requestJson;
            }

            if (requestJson.has("sortBy")) {
                sortBy = requestJson.getString("sortBy");
            } else if (filters.has("sortBy")) {
                sortBy = filters.getString("sortBy");
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

        // Standardize input keys
        String hotelType = filters.optString("type", filters.optString("hotelType", "")).toLowerCase().trim();
        String searchQuery = filters.optString("searchQuery", filters.optString("query", "")).toLowerCase().trim();

        // Define categories to decide which tables to hit
        List<String> validHotelCategories = Arrays.asList("hotel", "resort", "lodge", "villa", "dormitory", "hotels");
        List<String> validPgCategories = Arrays.asList("pg", "payingguest", "paying guest", "pgs");
        
        boolean searchHotels = true;
        boolean searchPGs = true;

        // Logic to prevent searching everything if a specific type is requested
        if (!hotelType.isEmpty() && !hotelType.equals("all")) {
            if (validPgCategories.contains(hotelType)) searchHotels = false;
            else if (validHotelCategories.contains(hotelType)) searchPGs = false;
        } else if (!searchQuery.isEmpty()) {
            if (validHotelCategories.contains(searchQuery)) searchPGs = false;
            else if (validPgCategories.contains(searchQuery)) searchHotels = false;
        }

        // Fetch from Hotels
        if (searchHotels) {
            combinedResults.putAll(getFilteredData("hotels_info", "Hotel_Name", "Hotel_Type", filters, sortBy));
        }
        // Fetch from PGs
        if (searchPGs) {
            combinedResults.putAll(getFilteredData("paying_guest_info", "pg_name", "pg_type", filters, sortBy));
        }

        return combinedResults;
    }

    private JSONArray getFilteredData(String tableName, String nameCol, String typeCol, JSONObject filters, String sortBy) throws SQLException {
        JSONArray dataArray = new JSONArray();
        List<Object> params = new ArrayList<>();
        
        // Base SQL
        StringBuilder query = new StringBuilder("SELECT * FROM " + tableName + " WHERE status = 'Active'");

        String searchQuery = filters.optString("searchQuery", filters.optString("query", "")).trim();
        String cityFilter = filters.optString("city", "").trim();

        // 1. Enhanced Keyword Search (Name, City, State, Country, Rating)
        if (!searchQuery.isEmpty()) {
            query.append(" AND (LOWER(").append(nameCol).append(") LIKE ? ")
                 .append("OR LOWER(city) LIKE ? ")
                 .append("OR LOWER(state) LIKE ? ")
                 .append("OR LOWER(country) LIKE ? ")
                 .append("OR CAST(rating AS CHAR) LIKE ?)");
            
            String wildcard = "%" + searchQuery.toLowerCase() + "%";
            params.add(wildcard); // Name
            params.add(wildcard); // City
            params.add(wildcard); // State
            params.add(wildcard); // Country
            params.add(wildcard); // Rating (as string match)
        }

        // 2. Specific City Filter (from Location Selector)
        if (!cityFilter.isEmpty()) {
            query.append(" AND (LOWER(city) = ? OR LOWER(state) = ?)");
            params.add(cityFilter.toLowerCase());
            params.add(cityFilter.toLowerCase());
        }

        // 3. Price Filter Logic
        double minPrice = filters.optDouble("minPrice", 0);
        double maxPrice = filters.optDouble("maxPrice", 0);
        // Logic to strip currency symbols for numerical comparison
        String numericPriceSql = "CAST(REPLACE(REPLACE(room_price, '₹', ''), ',', '') AS DECIMAL(10,2))";

        if (minPrice > 0) {
            query.append(" AND ").append(numericPriceSql).append(" >= ?");
            params.add(minPrice);
        }
        if (maxPrice > 0) {
            query.append(" AND ").append(numericPriceSql).append(" <= ?");
            params.add(maxPrice);
        }

        // 4. Standalone Rating Filter
        if (filters.has("rating") && !filters.isNull("rating")) {
            double r = filters.optDouble("rating", 0);
            if (r > 0) {
                query.append(" AND rating >= ?");
                params.add(r);
            }
        }

        // 5. Sorting Logic
        if (sortBy != null && !sortBy.equals("none")) {
            switch (sortBy) {
                case "price_lowest": query.append(" ORDER BY ").append(numericPriceSql).append(" ASC"); break;
                case "price_highest": query.append(" ORDER BY ").append(numericPriceSql).append(" DESC"); break;
                case "top_rated": query.append(" ORDER BY rating DESC"); break;
            }
        }

        query.append(" LIMIT 100");

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
                
                // Unified Mapping for Flutter UI
                if (tableName.equalsIgnoreCase("paying_guest_info")) {
                    obj.put("display_name", rs.getString("pg_name"));
                    obj.put("display_type", rs.getString("pg_type"));
                    obj.put("category_tag", "PG");
                } else {
                    obj.put("display_name", rs.getString("Hotel_Name"));
                    obj.put("display_type", rs.getString("Hotel_Type"));
                    obj.put("category_tag", "Hotel");
                }
                dataArray.put(obj);
            }
        }
        return dataArray;
    }

    private void sendJsonResponse(HttpExchange exchange, String response, int statusCode) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = response.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendResponse(HttpExchange exchange, String response, int statusCode) throws IOException {
        byte[] bytes = response.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
