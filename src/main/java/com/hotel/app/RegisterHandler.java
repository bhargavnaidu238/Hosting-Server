package com.hotel.app;

import com.hotel.security.PasswordUtil;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;

public class RegisterHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public RegisterHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try {
            // ===== Read JSON =====
            String body;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                body = br.lines().reduce("", String::concat);
            }

            JSONObject json = new JSONObject(body);

            String email = json.getString("email").toLowerCase();
            String firstName = json.optString("firstName", null);
            String lastName = json.optString("lastName", null);
            String gender = json.optString("gender", null);
            String mobile = json.optString("mobile", null);
            String address = json.optString("address", null);
            String rawPassword = json.getString("password");

            // Consent must be Yes / No
            String consent = json.optString("consent", "No");
            if (!consent.equalsIgnoreCase("Yes") && !consent.equalsIgnoreCase("No")) {
                sendResponse(exchange, 400, "Consent must be Yes or No");
                return;
            }

            String hashedPassword = PasswordUtil.hashPassword(rawPassword);

            try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {
                conn.setAutoCommit(false);

                // ===== Check email =====
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT 1 FROM user_info WHERE user_email = ?")) {
                    ps.setString(1, email);
                    if (ps.executeQuery().next()) {
                        sendResponse(exchange, 400, "Email already exists");
                        return;
                    }
                }

                // ===== Generate user_id (CR series) =====
                String newUserId = "CR9087601";

                String idSql = "SELECT user_id FROM user_info ORDER BY user_id DESC LIMIT 1";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(idSql)) {

                    if (rs.next()) {
                        String lastId = rs.getString("user_id"); // CR9087601
                        int num = Integer.parseInt(lastId.substring(2)) + 1;
                        newUserId = "CR" + num;
                    }
                }

                // ===== Insert user =====
                String insertSql = """
                    INSERT INTO user_info
                    (user_id, user_email, password, first_name, last_name,
                     gender, mobile_number, address, consent)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::yes_no_enum)
                """;

                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, newUserId);
                    ps.setString(2, email);
                    ps.setString(3, hashedPassword);
                    ps.setString(4, firstName);
                    ps.setString(5, lastName);
                    ps.setString(6, gender);
                    ps.setString(7, mobile);
                    ps.setString(8, address);
                    ps.setString(9, consent);
                    ps.executeUpdate();
                }

                // ===== Create wallet =====
                String walletSql = """
                    INSERT INTO wallets
                    (wallet_id, user_id, balance, status)
                    VALUES (?, ?, ?, ?)
                """;

                try (PreparedStatement ps = conn.prepareStatement(walletSql)) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, newUserId);
                    ps.setBigDecimal(3, new java.math.BigDecimal("200.00"));
                    ps.setString(4, "Active");
                    ps.executeUpdate();
                }

                conn.commit();
            }

            sendResponse(exchange, 200, "Registration Successful");

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "Registration Failed");
        }
    }

    private void sendResponse(HttpExchange exchange, int code, String msg) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
