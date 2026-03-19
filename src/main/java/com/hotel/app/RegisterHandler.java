package com.hotel.app;

import com.hotel.security.PasswordUtil;
import com.hotel.utilities.DbConfig;
import com.hotel.notification.service.EmailService; // Ensure this import exists
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
            String body;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                body = br.lines().reduce("", String::concat);
            }

            JSONObject json = new JSONObject(body);
            String email = json.getString("email").toLowerCase().trim();
            String firstName = json.optString("firstName", "User");
            String lastName = json.optString("lastName", "");
            String gender = json.optString("gender", null);
            String mobile = json.optString("mobile", null);
            String address = json.optString("address", null);
            String rawPassword = json.getString("password");
            String consent = json.optString("consent", "No");

            if (!consent.equalsIgnoreCase("Yes") && !consent.equalsIgnoreCase("No")) {
                sendResponse(exchange, 400, "Consent must be Yes or No");
                return;
            }

            String hashedPassword = PasswordUtil.hashPassword(rawPassword);
            String finalUserId;

            try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {
                conn.setAutoCommit(false);

                // 1. Check if email exists
                try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM user_info WHERE LOWER(user_email) = ?")) {
                    ps.setString(1, email);
                    if (ps.executeQuery().next()) {
                        sendResponse(exchange, 409, "Email already exists"); // Use 409 Conflict
                        return;
                    }
                }

                // 2. Generate user_id
                String newUserId = "CR9087601";
                String idSql = "SELECT user_id FROM user_info ORDER BY user_id DESC LIMIT 1";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(idSql)) {
                    if (rs.next()) {
                        String lastId = rs.getString("user_id");
                        int num = Integer.parseInt(lastId.substring(2)) + 1;
                        newUserId = "CR" + num;
                    }
                }
                finalUserId = newUserId;

                // 3. Insert user
                String insertSql = """
                    INSERT INTO user_info 
                    (user_id, user_email, password, first_name, last_name, 
                     gender, mobile_number, address, consent) 
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::yes_no_enum)
                """;

                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, finalUserId);
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

                // 4. Create wallet
                String walletSql = "INSERT INTO wallets (wallet_id, user_id, balance, status) VALUES (?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(walletSql)) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, finalUserId);
                    ps.setBigDecimal(3, new java.math.BigDecimal("200.00"));
                    ps.setString(4, "Active");
                    ps.executeUpdate();
                }

                /* ===============================
                 * 5. CLEANUP OTP Record
                 * =============================== */
                // Note: Using PartnerDataSource if OTP table is there, otherwise CustomerDataSource
                try (Connection otpConn = dbConfig.getPartnerDataSource().getConnection()) {
                    String deleteOtp = "DELETE FROM email_verification_otp WHERE LOWER(email) = ?";
                    try (PreparedStatement deleteStmt = otpConn.prepareStatement(deleteOtp)) {
                        deleteStmt.setString(1, email);
                        deleteStmt.executeUpdate();
                    }
                }

                conn.commit();
            }

            /* ===============================
             * 6. ASYNC WELCOME EMAIL
             * =============================== */
            new Thread(() -> {
                try {
                    EmailService emailService = new EmailService(dbConfig.getEmailApiKey(), dbConfig.getSenderEmail());
                    String subject = "Welcome to Hotel Booking";
                    String welcomeBody = "Hello " + firstName + " " + lastName + ",\n\nYour registration is successful.\nYour User ID: " + finalUserId + "\n\nRegards,\nTeam Hotel Booking";
                    emailService.sendEmail(email, subject, welcomeBody);
                } catch (Exception e) {
                    System.err.println("Async Welcome Email Failed: " + e.getMessage());
                }
            }).start();

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