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

            JSONArray result = fetchHotelsWithFilters(filters, sortBy);
            sendJsonResponse(exchange, result.toString(), 200);

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, "Error: " + e.getMessage(), 500);
        }
    }

    private JSONArray fetchHotelsWithFilters(JSONObject filters, String sortBy) throws SQLException {
        JSONArray hotelsArray = new JSONArray();
        // Start with a clean base query
        StringBuilder baseQuery = new StringBuilder("SELECT * FROM hotels_info WHERE Status = 'Active'");
        List<Object> params = new ArrayList<>();

        // 1. Keyword Search (Fixes Search Bar)
        if (filters.has("searchQuery") && !filters.optString("searchQuery").isEmpty()) {
            String query = "%" + filters.getString("searchQuery").toLowerCase() + "%";
            baseQuery.append(" AND (LOWER(Hotel_Name) LIKE ? OR LOWER(city) LIKE ? OR LOWER(Area) LIKE ?)");
            params.add(query); params.add(query); params.add(query);
        }

        // 2. City/Location Filter
        if (filters.has("city") && !filters.optString("city").trim().isEmpty()) {
            String cityLike = "%" + filters.getString("city").toLowerCase() + "%";
            baseQuery.append(" AND (LOWER(city) LIKE ? OR LOWER(state) LIKE ?)");
            params.add(cityLike);
            params.add(cityLike);
        }

        // 3. Hotel Type (Fixed Casing Mismatch)
        String hTypeKey = filters.has("hotelType") ? "hotelType" : "hoteltype";
        if (filters.has(hTypeKey) && !filters.optString(hTypeKey).isEmpty()) {
            baseQuery.append(" AND LOWER(Hotel_Type) = ?");
            params.add(filters.getString(hTypeKey).toLowerCase());
        }

        // 4. Sorting Logic
        String orderClause = "";
        if (sortBy != null && !sortBy.isEmpty()) {
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
