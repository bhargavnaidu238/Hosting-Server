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
import java.math.BigDecimal;

public class RegisterHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public RegisterHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try {
            // ===== Read JSON body =====
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }

            JSONObject json = new JSONObject(sb.toString());

            String email = json.getString("email");
            String firstName = json.getString("firstName");
            String lastName = json.getString("lastName");
            String gender = json.getString("gender");
            String mobile = json.getString("mobile");
            String address = json.getString("address");
            String rawPassword = json.getString("password");

            // ✅ CONSENT FIX (String → Boolean)
            boolean consent = parseConsent(json.get("consent"));

            // ✅ Hash password
            String hashedPassword = PasswordUtil.hashPassword(rawPassword);

            try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {

                conn.setAutoCommit(false);

                // ===== Check email =====
                String checkSql = "SELECT 1 FROM user_info WHERE user_email = ?";
                try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                    ps.setString(1, email);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            sendResponse(exchange, 400, "Email already exists");
                            return;
                        }
                    }
                }

                // ===== Insert user =====
                String insertUserSql = """
                        INSERT INTO user_info
                        (user_email, password, first_name, last_name,
                         gender, mobile_number, address, consent)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING user_id
                        """;

                int userId;

                try (PreparedStatement ps = conn.prepareStatement(insertUserSql)) {
                    ps.setString(1, email);
                    ps.setString(2, hashedPassword);
                    ps.setString(3, firstName.toUpperCase());
                    ps.setString(4, lastName.toUpperCase());
                    ps.setString(5, gender);
                    ps.setString(6, mobile);
                    ps.setString(7, address);
                    ps.setBoolean(8, consent);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("User creation failed");
                        }
                        userId = rs.getInt("user_id");
                    }
                }

                // ===== Create wallet =====
                String walletSql = """
                        INSERT INTO wallets
                        (wallet_id, user_id, balance, status)
                        VALUES (?, ?, ?, ?)
                        """;

                try (PreparedStatement ps = conn.prepareStatement(walletSql)) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setInt(2, userId);
                    ps.setBigDecimal(3, new BigDecimal("200.00"));
                    ps.setString(4, "active");
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

    // ✅ Robust consent parser
    private boolean parseConsent(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            String v = ((String) value).trim().toLowerCase();
            return v.equals("yes") || v.equals("true") || v.equals("1");
        }
        return false;
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
