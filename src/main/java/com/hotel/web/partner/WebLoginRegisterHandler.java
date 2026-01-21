package com.hotel.web.partner;

import com.hotel.security.PasswordUtil;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class WebLoginRegisterHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public WebLoginRegisterHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String body;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            body = sb.toString();
        }

        Map<String, String> params = parseForm(body);
        String path = exchange.getRequestURI().getPath();

        try {
            switch (path) {
                case "/login":
                    handleLogin(exchange, params);
                    break;

                case "/register":
                    handleRegister(exchange, params);
                    break;

                case "/forgotpassword":
                    handleForgotPassword(exchange, params);
                    break;

                case "/webgetprofile":
                    handleGetProfile(exchange,
                            params.getOrDefault("email", "").trim().toLowerCase());
                    break;

                default:
                    sendResponse(exchange, 404,
                            "{\"status\":\"error\",\"message\":\"Invalid endpoint\"}");
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500,
                    "{\"status\":\"error\",\"message\":\"Internal server error\"}");
        }
    }

    // ================== LOGIN ==================
    private void handleLogin(HttpExchange exchange, Map<String, String> params)
            throws IOException, SQLException {

        String email = params.getOrDefault("email", "").trim().toLowerCase();
        String rawPassword = params.getOrDefault("password", "").trim();

        if (email.isEmpty() || rawPassword.isEmpty()) {
            sendResponse(exchange, 400,
                    "{\"status\":\"error\",\"message\":\"Email and password required\"}");
            return;
        }

        String query = "SELECT * FROM partner_data WHERE LOWER(email)=?";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, email);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    sendResponse(exchange, 404,
                            "{\"status\":\"error\",\"message\":\"User not found\"}");
                    return;
                }

                String storedHash = rs.getString("password");
                String status = rs.getString("status");

                // 🔐 bcrypt verification (FIX)
                if (storedHash == null ||
                        !PasswordUtil.verifyPassword(rawPassword, storedHash.trim())) {

                    sendResponse(exchange, 401,
                            "{\"status\":\"error\",\"message\":\"Invalid credentials\"}");
                    return;
                }

                if (!"Active".equalsIgnoreCase(status)) {
                    sendResponse(exchange, 403,
                            "{\"status\":\"error\",\"message\":\"User inactive\"}");
                    return;
                }

                Map<String, String> response = new LinkedHashMap<>();
                ResultSetMetaData meta = rs.getMetaData();

                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    response.put(meta.getColumnName(i),
                            Optional.ofNullable(rs.getString(i)).orElse(""));
                }

                sendResponse(exchange, 200, buildJson("success", "Login successful", response));
            }
        }
    }

    // ================== REGISTER ==================
    private void handleRegister(HttpExchange exchange, Map<String, String> params)
            throws IOException, SQLException {

        String email = params.getOrDefault("email", "").trim().toLowerCase();
        String rawPassword = params.getOrDefault("password", "").trim();

        if (email.isEmpty() || rawPassword.isEmpty()) {
            sendResponse(exchange, 400,
                    "{\"status\":\"error\",\"message\":\"Email & password required\"}");
            return;
        }

        String hashedPassword = PasswordUtil.hashPassword(rawPassword);

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection()) {

            try (PreparedStatement check =
                         conn.prepareStatement("SELECT 1 FROM partner_data WHERE LOWER(email)=?")) {
                check.setString(1, email);
                if (check.executeQuery().next()) {
                    sendResponse(exchange, 409,
                            "{\"status\":\"error\",\"message\":\"Email already registered\"}");
                    return;
                }
            }

            String partnerId = "PR" + (10000 + new Random().nextInt(90000));

            String insert =
                    "INSERT INTO partner_data " +
                    "(partner_id, email, password, status, registration_date) " +
                    "VALUES (?, ?, ?, ?, ?)";

            try (PreparedStatement stmt = conn.prepareStatement(insert)) {
                stmt.setString(1, partnerId);
                stmt.setString(2, email);
                stmt.setString(3, hashedPassword);
                stmt.setString(4, "Active");
                stmt.setTimestamp(5, new Timestamp(System.currentTimeMillis()));
                stmt.executeUpdate();
            }
        }

        sendResponse(exchange, 200,
                "{\"status\":\"success\",\"message\":\"Registration successful\"}");
    }

    // ================== FORGOT PASSWORD ==================
    private void handleForgotPassword(HttpExchange exchange, Map<String, String> params)
            throws IOException, SQLException {

        String email = params.getOrDefault("email", "").trim().toLowerCase();
        String newPassword = params.getOrDefault("newPassword", "").trim();

        if (email.isEmpty() || newPassword.isEmpty()) {
            sendResponse(exchange, 400,
                    "{\"status\":\"error\",\"message\":\"Invalid input\"}");
            return;
        }

        String hash = PasswordUtil.hashPassword(newPassword);

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt =
                     conn.prepareStatement("UPDATE partner_data SET password=? WHERE LOWER(email)=?")) {

            stmt.setString(1, hash);
            stmt.setString(2, email);

            if (stmt.executeUpdate() == 0) {
                sendResponse(exchange, 404,
                        "{\"status\":\"error\",\"message\":\"User not found\"}");
                return;
            }
        }

        sendResponse(exchange, 200,
                "{\"status\":\"success\",\"message\":\"Password updated\"}");
    }

    // ================== PROFILE ==================
    private void handleGetProfile(HttpExchange exchange, String email)
            throws IOException, SQLException {

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt =
                     conn.prepareStatement("SELECT * FROM partner_data WHERE LOWER(email)=?")) {

            stmt.setString(1, email);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    sendResponse(exchange, 404,
                            "{\"status\":\"error\",\"message\":\"Not found\"}");
                    return;
                }

                Map<String, String> profile = new LinkedHashMap<>();
                ResultSetMetaData meta = rs.getMetaData();

                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    profile.put(meta.getColumnName(i),
                            Optional.ofNullable(rs.getString(i)).orElse(""));
                }

                sendResponse(exchange, 200, buildJson(null, null, profile));
            }
        }
    }

    // ================== UTIL ==================
    private Map<String, String> parseForm(String body) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;

        for (String pair : body.split("&")) {
            String[] p = pair.split("=", 2);
            if (p.length == 2) {
                map.put(URLDecoder.decode(p[0], "UTF-8"),
                        URLDecoder.decode(p[1], "UTF-8"));
            }
        }
        return map;
    }

    private void sendResponse(HttpExchange exchange, int code, String body)
            throws IOException {

        exchange.getResponseHeaders().set(
                "Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String buildJson(String status, String message, Map<String, String> data) {
        StringBuilder sb = new StringBuilder("{");
        if (status != null) sb.append("\"status\":\"").append(status).append("\",");
        if (message != null) sb.append("\"message\":\"").append(message).append("\",");

        for (Map.Entry<String, String> e : data.entrySet()) {
            sb.append("\"").append(e.getKey()).append("\":\"")
                    .append(e.getValue().replace("\"", "\\\"")).append("\",");
        }
        sb.setLength(sb.length() - 1);
        sb.append("}");
        return sb.toString();
    }
}
