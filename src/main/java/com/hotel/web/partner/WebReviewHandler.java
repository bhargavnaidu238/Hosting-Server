package com.hotel.web.partner;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;

public class WebReviewHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public WebReviewHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Add CORS Headers
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        try {
            String email = "";

            // Logic to read from URL (GET) or Body (POST)
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getRawQuery();
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] pair = param.split("=", 2);
                        if (pair.length == 2 && "email".equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))) {
                            email = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
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
                    String bodyStr = body.toString();
                    if (bodyStr.contains("email=")) {
                        for (String param : bodyStr.split("&")) {
                            String[] pair = param.split("=", 2);
                            if (pair.length == 2 && "email".equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))) {
                                email = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                            }
                        }
                    } else {
                        JSONObject requestJson = new JSONObject(bodyStr);
                        email = requestJson.optString("email", "");
                    }
                }
            }

            if (email.isEmpty()) {
                sendJsonResponse(exchange, new JSONObject().put("status", "error").put("message", "Email is required").toString(), 400);
                return;
            }

            JSONArray reviews = fetchPartnerReviews(email);
            JSONObject response = new JSONObject();
            response.put("status", "success");
            response.put("data", reviews);
            
            sendJsonResponse(exchange, response.toString(), 200);

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, "Error processing reviews: " + e.getMessage(), 500);
        }
    }

    private JSONArray fetchPartnerReviews(String email) throws SQLException {
        JSONArray reviewList = new JSONArray();

        // SQL: Join with user_info and use COALESCE to safely handle NULL names
        String query = "SELECT r.review_id, r.rating, r.comment, r.created_at, r.user_id, r.hotel_id, " +
                       "(COALESCE(u.first_name, '') || ' ' || COALESCE(u.last_name, '')) AS user_name, " +
                       "COALESCE(h.Hotel_Name, p.pg_name) AS property_name, " +
                       "COALESCE(h.city, p.city) AS property_city, " +
                       "CASE WHEN h.Hotel_ID IS NOT NULL THEN 'Hotel' ELSE 'PG' END AS property_type " +
                       "FROM reviews r " +
                       "INNER JOIN user_info u ON r.user_id = u.user_id " +
                       "LEFT JOIN hotels_info h ON r.hotel_id = h.Hotel_ID " +
                       "LEFT JOIN paying_guest_info p ON r.hotel_id = p.pg_id " +
                       "WHERE h.Partner_ID = (SELECT partner_id FROM partner_data WHERE LOWER(email) = LOWER(?)) " +
                       "OR p.partner_id = (SELECT partner_id FROM partner_data WHERE LOWER(email) = LOWER(?)) " +
                       "ORDER BY r.created_at DESC";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            
            stmt.setString(1, email);
            stmt.setString(2, email);
            
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                JSONObject review = new JSONObject();
                review.put("review_id", rs.getLong("review_id"));
                review.put("rating", rs.getInt("rating"));
                review.put("comment", rs.getString("comment") == null ? "" : rs.getString("comment"));
                review.put("created_at", rs.getTimestamp("created_at").toString());
                review.put("user_id", rs.getString("user_id"));
                review.put("user_name", rs.getString("user_name").trim()); // Sends combined name to Flutter
                review.put("hotel_id", rs.getString("hotel_id"));
                review.put("property_name", rs.getString("property_name"));
                review.put("property_city", rs.getString("property_city"));
                review.put("property_type", rs.getString("property_type"));
                
                reviewList.put(review);
            }
        }
        return reviewList;
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