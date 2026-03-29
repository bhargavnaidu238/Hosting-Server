package com.hotel.app;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class ReviewsHandler implements HttpHandler {
    private final DbConfig dbConfig;

    public ReviewsHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String method = exchange.getRequestMethod();
        try {
            if ("GET".equalsIgnoreCase(method)) {
                handleGetReviews(exchange);
            } else if ("POST".equalsIgnoreCase(method)) {
                handlePostReview(exchange);
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Server Error: " + e.getMessage());
        }
    }

    private void handlePostReview(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> data = parseJsonBody(body);

        String hotelId = data.get("hotel_id");
        String userId = data.get("user_id");

        if (hotelId == null || userId == null || data.get("rating") == null) {
            sendError(exchange, 400, "Missing required fields");
            return;
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection()) {
            // 1. DUPLICATE VALIDATION
            String checkSql = "SELECT count(*) FROM reviews WHERE hotel_id = ? AND user_id = ?";
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setString(1, hotelId);
                checkStmt.setString(2, userId);
                ResultSet rs = checkStmt.executeQuery();
                if (rs.next() && rs.getInt(1) > 0) {
                    sendError(exchange, 409, "You have already provided the review. Please delete the previous review in order to give new feedback.");
                    return;
                }
            }

            conn.setAutoCommit(false); 

            // 2. Insert into Reviews table
            String insertSql = "INSERT INTO reviews (hotel_id, user_id, rating, comment) VALUES (?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                stmt.setString(1, hotelId);
                stmt.setString(2, userId);
                stmt.setInt(3, Integer.parseInt(data.get("rating")));
                stmt.setString(4, data.get("comment") != null ? data.get("comment") : "");
                stmt.executeUpdate();
            }

            // 3. Update Summary Tables (Logic to detect if it's a Hotel or PG)
            String summaryTable;
            String idColumn;
            
            if (hotelId.startsWith("PG")) {
                summaryTable = "paying_guest_info";
                idColumn = "pg_id";
            } else {
                summaryTable = "hotels_info";
                idColumn = "Hotel_ID";
            }

            String updateSummarySql = "UPDATE " + summaryTable + " SET " +
                                      "avg_rating = (SELECT COALESCE(AVG(rating), 0) FROM reviews WHERE hotel_id = ?), " +
                                      "total_reviews = (SELECT COUNT(*) FROM reviews WHERE hotel_id = ?) " +
                                      "WHERE " + idColumn + " = ?";
                                      
            try (PreparedStatement stmt = conn.prepareStatement(updateSummarySql)) {
                stmt.setString(1, hotelId);
                stmt.setString(2, hotelId);
                stmt.setString(3, hotelId);
                stmt.executeUpdate();
            }

            conn.commit();
            sendSimpleResponse(exchange, 201, "{\"message\":\"Review added successfully\"}");
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Database Error: " + e.getMessage());
        }
    }

    private void handleGetReviews(HttpExchange exchange) throws IOException, SQLException {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = parseQueryParams(query);
        String hotelId = params.get("hotel_id");

        if (hotelId == null) {
            sendError(exchange, 400, "Missing hotel_id");
            return;
        }

        List<Map<String, Object>> reviewsList = new ArrayList<>();
        String sql = "SELECT r.*, (u.first_name || ' ' || u.last_name) as user_name FROM reviews r " +
                     "JOIN user_info u ON r.user_id = u.user_id " +
                     "WHERE r.hotel_id = ? " +
                     "ORDER BY r.rating DESC, r.created_at DESC " +
                     "LIMIT 10";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, hotelId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("review_id", rs.getLong("review_id"));
                row.put("user_name", rs.getString("user_name"));
                row.put("rating", rs.getInt("rating"));
                row.put("comment", rs.getString("comment"));
                row.put("created_at", rs.getTimestamp("created_at").toString());
                reviewsList.add(row);
            }
        }
        sendJson(exchange, reviewsList);
    }

    private Map<String, String> parseJsonBody(String body) {
        Map<String, String> map = new HashMap<>();
        body = body.trim();
        if (body.startsWith("{")) body = body.substring(1);
        if (body.endsWith("}")) body = body.substring(0, body.length() - 1);
        String[] pairs = body.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        for (String pair : pairs) {
            String[] kv = pair.split(":(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            if (kv.length == 2) {
                map.put(kv[0].trim().replace("\"", ""), kv[1].trim().replace("\"", ""));
            }
        }
        return map;
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null) return params;
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length > 1) params.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return params;
    }

    private void sendJson(HttpExchange exchange, List<Map<String, Object>> list) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> m = list.get(i);
            sb.append("{");
            int j = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                sb.append("\"").append(e.getKey()).append("\":\"").append(escape(String.valueOf(e.getValue()))).append("\"");
                if (j++ < m.size() - 1) sb.append(",");
            }
            sb.append("}");
            if (i < list.size() - 1) sb.append(",");
        }
        sb.append("]");
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void sendSimpleResponse(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        String json = "{\"error\":\"" + escape(msg) + "\"}";
        sendSimpleResponse(exchange, code, json);
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}