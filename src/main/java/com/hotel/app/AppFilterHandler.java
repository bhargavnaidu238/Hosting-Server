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

            JSONObject filters = requestJson.optJSONObject("filters");
            if (filters == null) {
                filters = requestJson;
            }

            String sortBy = requestJson.optString("sortBy", "none");

            JSONArray result = fetchDataWithFilters(filters, sortBy);

            sendJsonResponse(exchange, result.toString(), 200);

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, e.getMessage(), 500);
        }
    }

    private JSONArray fetchDataWithFilters(JSONObject filters, String sortBy) throws SQLException {

        JSONArray combined = new JSONArray();

        combined.putAll(getFilteredData(
                "hotels_info",
                "Hotel_Name",
                "Hotel_Type",
                "Room_Price",
                filters,
                sortBy
        ));

        combined.putAll(getFilteredData(
                "paying_guest_info",
                "pg_name",
                "pg_type",
                "room_price",
                filters,
                sortBy
        ));

        return combined;
    }

    private JSONArray getFilteredData(
            String table,
            String nameCol,
            String typeCol,
            String priceCol,
            JSONObject filters,
            String sortBy) throws SQLException {

        JSONArray array = new JSONArray();
        List<Object> params = new ArrayList<>();

        StringBuilder query = new StringBuilder("SELECT * FROM " + table + " WHERE status='Active'");

        String searchQuery = filters.optString("searchQuery", "").trim();
        String city = filters.optString("city", "").trim();

        double minPrice = filters.optDouble("minPrice", 0);
        double maxPrice = filters.optDouble("maxPrice", 0);
        double rating = filters.optDouble("rating", 0);

        // SEARCH FILTER
        if (!searchQuery.isEmpty()) {

            query.append(" AND (LOWER(").append(nameCol).append(") LIKE ?")
                    .append(" OR LOWER(city) LIKE ?")
                    .append(" OR LOWER(state) LIKE ?")
                    .append(" OR LOWER(country) LIKE ?)");

            String q = "%" + searchQuery.toLowerCase() + "%";

            params.add(q);
            params.add(q);
            params.add(q);
            params.add(q);
        }

        // CITY FILTER
        if (!city.isEmpty()) {

            query.append(" AND (LOWER(city)=? OR LOWER(state)=?)");

            params.add(city.toLowerCase());
            params.add(city.toLowerCase());
        }

        // PRICE FILTER (CHECKS ALL VALUES IN COMMA LIST)
        if (minPrice > 0 && maxPrice > 0) {

            query.append(" AND (");

            for (int i = 1; i <= 5; i++) {

                if (i > 1) query.append(" OR ");

                query.append("CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(")
                        .append(priceCol)
                        .append(", ',', ")
                        .append(i)
                        .append("), ',', -1) AS DECIMAL(10,2)) BETWEEN ? AND ?");

                params.add(minPrice);
                params.add(maxPrice);
            }

            query.append(")");
        }

        // RATING FILTER
        if (rating > 0) {

            query.append(" AND rating >= ?");
            params.add(rating);
        }

        // SORTING
        if (sortBy != null && !sortBy.equals("none")) {

            String firstPrice = "CAST(SUBSTRING_INDEX(" + priceCol + ", ',', 1) AS DECIMAL(10,2))";

            switch (sortBy) {

                case "price_lowest":
                    query.append(" ORDER BY ").append(firstPrice).append(" ASC");
                    break;

                case "price_highest":
                    query.append(" ORDER BY ").append(firstPrice).append(" DESC");
                    break;

                case "top_rated":
                    query.append(" ORDER BY rating DESC");
                    break;
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

                    obj.put(col.toLowerCase(),
                            val == null ? JSONObject.NULL : val);
                }

                if (table.equalsIgnoreCase("paying_guest_info")) {

                    obj.put("display_name", rs.getString("pg_name"));
                    obj.put("display_type", rs.getString("pg_type"));
                    obj.put("category_tag", "PG");

                } else {

                    obj.put("display_name", rs.getString("Hotel_Name"));
                    obj.put("display_type", rs.getString("Hotel_Type"));
                    obj.put("category_tag", "Hotel");
                }

                array.put(obj);
            }
        }

        return array;
    }

    private void sendJsonResponse(HttpExchange exchange, String response, int statusCode) throws IOException {

        exchange.getResponseHeaders().set("Content-Type", "application/json");

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