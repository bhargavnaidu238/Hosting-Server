package com.hotel.web.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.security.PasswordUtil;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class WebProfileHandler implements HttpHandler {

    private static final Set<String> READ_ONLY_FIELDS =
            Set.of("email", "user_status", "registration_date", "partner_id");

    private final DbConfig dbConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebProfileHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        setCORS(exchange);

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("status", "error", "message", "Invalid request method"));
            return;
        }

        String body = readRequestBody(exchange);
        Map<String, String> params = parseForm(body);
        String path = exchange.getRequestURI().getPath();

        try {
            switch (path) {
                case "/webgetprofile" -> handleGetProfile(exchange, params);
                case "/webupdateprofile" -> handleUpdateProfile(exchange, params);
                case "/webchangepassword" -> handleChangePassword(exchange, params);
                case "/webdeleteprofile" -> handleDeleteProfile(exchange, params);
                default -> sendJson(exchange, 404, Map.of("status", "error", "message", "Invalid endpoint"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, 500, Map.of("status", "error", "message", "Internal Server Error: " + e.getMessage()));
        }
    }

    private void handleGetProfile(HttpExchange exchange, Map<String, String> params) throws Exception {
        String loggedInEmail = params.getOrDefault("loggedInEmail", "").trim().toLowerCase();
        if (loggedInEmail.isEmpty()) {
            sendJson(exchange, 400, Map.of("status", "error", "message", "Email is required"));
            return;
        }

        String query = "SELECT * FROM partner_data WHERE LOWER(email)=?";
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, loggedInEmail);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    sendJson(exchange, 404, Map.of("status", "error", "message", "Partner not found"));
                    return;
                }

                Map<String, Object> data = new LinkedHashMap<>();
                ResultSetMetaData meta = rs.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String col = meta.getColumnName(i).toLowerCase();
                    Object val = rs.getObject(i);
                    // Standardize nulls and dates for Flutter
                    data.put(col, val != null ? val.toString() : "");
                }
                sendJson(exchange, 200, Map.of("status", "success", "data", data));
            }
        }
    }

    private void handleUpdateProfile(HttpExchange exchange, Map<String, String> params) throws Exception {
        String loggedInEmail = params.getOrDefault("loggedInEmail", "").trim().toLowerCase();
        if (loggedInEmail.isEmpty()) {
            sendJson(exchange, 400, Map.of("status", "error", "message", "Email is required"));
            return;
        }

        StringBuilder setClause = new StringBuilder();
        List<String> values = new ArrayList<>();

        // We only update fields sent in the request that aren't read-only
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey().toLowerCase();
            if (!READ_ONLY_FIELDS.contains(key) && !key.equals("loggedinemail")) {
                setClause.append(key).append("=?,");
                values.add(entry.getValue().trim());
            }
        }

        if (setClause.length() == 0) {
            sendJson(exchange, 400, Map.of("status", "error", "message", "No editable fields provided"));
            return;
        }
        setClause.setLength(setClause.length() - 1);

        String sql = "UPDATE partner_data SET " + setClause + " WHERE LOWER(email)=?";
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            int i = 1;
            for (String val : values) stmt.setString(i++, val);
            stmt.setString(i, loggedInEmail);

            if (stmt.executeUpdate() > 0) {
                sendJson(exchange, 200, Map.of("status", "success", "message", "Profile updated"));
            } else {
                sendJson(exchange, 404, Map.of("status", "error", "message", "User not found"));
            }
        }
    }

    private void handleChangePassword(HttpExchange exchange, Map<String, String> params) throws Exception {
        String email = params.getOrDefault("loggedInEmail", "").trim().toLowerCase();
        String currentPlain = params.getOrDefault("currentPassword", "");
        String newPlain = params.getOrDefault("newPassword", "");

        if (email.isEmpty() || currentPlain.isEmpty() || newPlain.isEmpty()) {
            sendJson(exchange, 400, Map.of("status", "error", "message", "Missing fields"));
            return;
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection()) {
            String storedHash = null;
            try (PreparedStatement stmt = conn.prepareStatement("SELECT password FROM partner_data WHERE LOWER(email)=?")) {
                stmt.setString(1, email);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) storedHash = rs.getString("password");
            }

            if (storedHash == null || !PasswordUtil.verifyPassword(currentPlain, storedHash)) {
                sendJson(exchange, 401, Map.of("status", "error", "message", "Current password incorrect"));
                return;
            }

            try (PreparedStatement stmt = conn.prepareStatement("UPDATE partner_data SET password=? WHERE LOWER(email)=?")) {
                stmt.setString(1, PasswordUtil.hashPassword(newPlain));
                stmt.setString(2, email);
                stmt.executeUpdate();
                sendJson(exchange, 200, Map.of("status", "success", "message", "Password changed"));
            }
        }
    }

    private void handleDeleteProfile(HttpExchange exchange, Map<String, String> params) throws Exception {
        String email = params.getOrDefault("loggedInEmail", "").trim().toLowerCase();
        // FIXED: Explicit cast to status_enum for Postgres compatibility
        String sql = "UPDATE partner_data SET user_status='Inactive'::status_enum WHERE LOWER(email)=?";
        
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            if (stmt.executeUpdate() > 0) {
                sendJson(exchange, 200, Map.of("status", "success", "message", "Account deactivated"));
            } else {
                sendJson(exchange, 404, Map.of("status", "error", "message", "User not found"));
            }
        }
    }

    // ================= UTILITIES =================

    private Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isBlank()) return map;
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            try {
                String key = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                String val = parts.length > 1 ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                map.put(key, val);
            } catch (Exception e) { /* skip malformed */ }
        }
        return map;
    }

    private void sendJson(HttpExchange exchange, int code, Object response) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void setCORS(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }
}