package com.hotel.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Map;

public class UserPreferenceHandler implements HttpHandler {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DbConfig dbConfig;

    public UserPreferenceHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORS Headers
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            handleSavePreference(exchange);
        } else {
            sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
        }
    }

    private void handleSavePreference(HttpExchange exchange) throws IOException {
        try {
            Map<String, Object> data = objectMapper.readValue(exchange.getRequestBody(), Map.class);
            String email = (String) data.get("email");

            if (email == null || email.isEmpty()) {
                sendResponse(exchange, 400, Map.of("error", "Email is required"));
                return;
            }

            String sql = """
                UPDATE user_info SET 
                    stay_type = ?, 
                    meal_preference = ?, 
                    add_ons = ?, 
                    travel_style = ?, 
                    stay_preference = ?, 
                    for_you = ?, 
                    location_preference = ?
                WHERE user_email = ?
                """;

            try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                stmt.setString(1, (String) data.get("stay_type"));
                stmt.setString(2, (String) data.get("meal_preference"));
                stmt.setString(3, (String) data.get("add_ons"));
                stmt.setString(4, (String) data.get("travel_style"));
                stmt.setString(5, (String) data.get("stay_preference"));
                stmt.setString(6, (String) data.get("for_you"));
                stmt.setString(7, (String) data.get("location_preference"));
                stmt.setString(8, email);

                int updated = stmt.executeUpdate();
                if (updated > 0) {
                    sendResponse(exchange, 200, Map.of("success", "Preferences updated successfully"));
                } else {
                    sendResponse(exchange, 404, Map.of("error", "User not found"));
                }
            }
        } catch (Exception e) {
            sendResponse(exchange, 500, Map.of("error", "Database Error: " + e.getMessage()));
        }
    }

    private void sendResponse(HttpExchange ex, int code, Object body) throws IOException {
        String json = objectMapper.writeValueAsString(body);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}