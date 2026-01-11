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

    // ✅ Inject DbConfig via constructor
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
            boolean consent = json.getBoolean("consent");

            // ✅ Hash password
            String hashedPassword = PasswordUtil.hashPassword(rawPassword);

            try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {

                conn.setAutoCommit(false); // ✅ transaction safety

                // ===== Check if email exists =====
                String checkSql = """
                        SELECT 1 FROM user_info WHERE user_email = ?
                        """;

                try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                    checkStmt.setString(1, email);
                    try (ResultSet rs = checkStmt.executeQuery()) {
                        if (rs.next()) {
                            sendResponse(exchange, 400, "Email already exists");
                            return;
                        }
                    }
                }

                // ===== Insert new user (PostgreSQL) =====
                String insertUserSql = """
                        INSERT INTO user_info
                        (user_email, password, first_name, last_name,
                         gender, mobile_number, address, consent)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING user_id
                        """;

                int generatedUserId;

                try (PreparedStatement insertStmt =
                             conn.prepareStatement(insertUserSql)) {

                    insertStmt.setString(1, email);
                    insertStmt.setString(2, hashedPassword);
                    insertStmt.setString(3, firstName.toUpperCase());
                    insertStmt.setString(4, lastName.toUpperCase());
                    insertStmt.setString(5, gender);
                    insertStmt.setString(6, mobile);
                    insertStmt.setString(7, address);
                    insertStmt.setBoolean(8, consent);

                    try (ResultSet rs = insertStmt.executeQuery()) {
                        if (rs.next()) {
                            generatedUserId = rs.getInt("user_id");
                        } else {
                            throw new SQLException("Failed to generate user_id");
                        }
                    }
                }

                // ===== Create wallet for user =====
                String walletSql = """
                        INSERT INTO wallets
                        (wallet_id, user_id, balance, status)
                        VALUES (?, ?, ?, ?)
                        """;

                try (PreparedStatement walletStmt = conn.prepareStatement(walletSql)) {
                    walletStmt.setString(1, UUID.randomUUID().toString());
                    walletStmt.setInt(2, generatedUserId);
                    walletStmt.setBigDecimal(3, new BigDecimal("200.00"));
                    walletStmt.setString(4, "active");
                    walletStmt.executeUpdate();
                }

                conn.commit(); // ✅ commit transaction
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
