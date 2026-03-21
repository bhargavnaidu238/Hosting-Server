package com.hotel.app;

import com.hotel.security.PasswordUtil;
import com.hotel.utilities.DbConfig;
import com.hotel.notification.service.EmailService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Random;

public class LoginHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public LoginHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Add CORS Headers for Flutter App compatibility
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();

        try {
            JSONObject json = new JSONObject(readRequestBody(exchange));

            if ("/login".equals(path)) {
                handleLogin(exchange, json);
                return;
            }

            if ("/app/forgot-password/verify".equals(path)) {
                handleForgotVerify(exchange, json);
                return;
            }

            if ("/app/forgot-password/change".equals(path)) {
                handleChangePassword(exchange, json);
                return;
            }

            exchange.sendResponseHeaders(404, -1);

        } catch (Exception e) {
            e.printStackTrace();
            sendJsonResponse(exchange, 500,
                    new JSONObject()
                            .put("error", "server_error")
                            .put("message", "Internal Server Error")
                            .toString());
        }
    }

    /* ================= LOGIN ================= */

    private void handleLogin(HttpExchange exchange, JSONObject json) throws Exception {
        String email = json.getString("email").trim().toLowerCase();
        String rawPassword = json.getString("password");

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {
            String sql = "SELECT * FROM user_info WHERE LOWER(user_email) = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        sendJsonResponse(exchange, 404, new JSONObject().put("error", "user_not_exists").toString());
                        return;
                    }

                    if ("Inactive".equalsIgnoreCase(rs.getString("status"))) {
                        sendJsonResponse(exchange, 403, new JSONObject().put("error", "inactive").toString());
                        return;
                    }

                    if (!PasswordUtil.verifyPassword(rawPassword, rs.getString("password"))) {
                        sendJsonResponse(exchange, 401, new JSONObject().put("error", "wrong_password").toString());
                        return;
                    }

                    JSONObject user = new JSONObject();
                    user.put("userId", rs.getString("user_id"));
                    user.put("firstName", rs.getString("first_name"));
                    user.put("lastName", rs.getString("last_name"));
                    user.put("email", rs.getString("user_email"));
                    user.put("mobile", rs.getString("mobile_number"));
                    user.put("address", rs.getString("address"));
                    sendJsonResponse(exchange, 200, user.toString());
                }
            }
        }
    }

    /* ================= FORGOT PASSWORD VERIFY (WITH OTP) ================= */

    private void handleForgotVerify(HttpExchange exchange, JSONObject json) throws Exception {
        String email = json.getString("email").trim().toLowerCase();
        String inputMobile = normalizeMobile(json.getString("mobile"));
        boolean matched = false;

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {
            String sql = "SELECT mobile_number FROM user_info WHERE LOWER(user_email) = ? AND status = 'Active'";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String dbRawMobile = rs.getString("mobile_number");
                        String dbNormalizedMobile = normalizeMobile(dbRawMobile);
                        
                        // Debugging logs to verify normalization on the server
                        System.out.println("[ForgotVerify] Input Normalized: " + inputMobile);
                        System.out.println("[ForgotVerify] DB Normalized: " + dbNormalizedMobile);
                        
                        matched = inputMobile.equals(dbNormalizedMobile);
                    }
                }
            }
        }

        if (matched) {
            try {
                triggerPasswordResetOtp(email);
                sendJsonResponse(exchange, 200, new JSONObject().put("matched", true).put("message", "OTP Sent").toString());
            } catch (Exception otpEx) {
                otpEx.printStackTrace();
                sendJsonResponse(exchange, 500, new JSONObject().put("error", "otp_failed").put("message", "Failed to generate or send OTP").toString());
            }
        } else {
            sendJsonResponse(exchange, 200, new JSONObject().put("matched", false).put("message", "Invalid Email or Mobile Number").toString());
        }
    }

    private void triggerPasswordResetOtp(String email) throws Exception {
        String otp = String.valueOf(new Random().nextInt(900000) + 100000);
        Timestamp expiry = new Timestamp(System.currentTimeMillis() + (2 * 60 * 1000)); 

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection()) {
            String query = """
                INSERT INTO email_verification_otp (email, otp_code, otp_expiry, attempts)
                VALUES (?, ?, ?, 1)
                ON CONFLICT (email) DO UPDATE SET
                otp_code = EXCLUDED.otp_code,
                otp_expiry = EXCLUDED.otp_expiry,
                attempts = email_verification_otp.attempts + 1
            """;
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, email.toLowerCase());
                stmt.setString(2, otp);
                stmt.setTimestamp(3, expiry);
                stmt.executeUpdate();
            }
        }

        EmailService emailService = new EmailService(dbConfig.getEmailApiKey(), dbConfig.getSenderEmail());
        emailService.sendEmail(email, "Password Reset OTP", "Your OTP for password reset is: " + otp + ". This code expires in 2 minutes.");
    }

    /* ================= CHANGE PASSWORD ================= */

    private void handleChangePassword(HttpExchange exchange, JSONObject json) throws Exception {
        String email = json.getString("email").trim().toLowerCase();
        String newPassword = json.getString("newPassword");
        String hashedPassword = PasswordUtil.hashPassword(newPassword);
        int updated = 0;

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {
            String sql = "UPDATE user_info SET password = ? WHERE LOWER(user_email) = ? AND status = 'Active'";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, hashedPassword);
                ps.setString(2, email);
                updated = ps.executeUpdate();
            }

            if (updated > 0) {
                try (Connection otpConn = dbConfig.getPartnerDataSource().getConnection()) {
                    String deleteOtp = "DELETE FROM email_verification_otp WHERE LOWER(email) = ?";
                    try (PreparedStatement deleteStmt = otpConn.prepareStatement(deleteOtp)) {
                        deleteStmt.setString(1, email);
                        deleteStmt.executeUpdate();
                    }
                }

                new Thread(() -> {
                    try {
                        EmailService emailService = new EmailService(dbConfig.getEmailApiKey(), dbConfig.getSenderEmail());
                        emailService.sendEmail(email, "Security Alert: Password Changed", "Your password for the Hotel Booking App was successfully updated\n\n"
                        		+ "If not performed by you, Please reach out to Customer Care immediately.");
                    } catch (Exception e) {
                        System.err.println("Async Security Email Failed: " + e.getMessage());
                    }
                }).start();
            }
        }

        sendJsonResponse(exchange, 200, new JSONObject().put("success", updated > 0).toString());
    }

    /* ================= UTIL METHODS ================= */

    private String normalizeMobile(String mobile) {
        if (mobile == null) return "";
        // Step 1: Remove all non-numeric characters (+, -, spaces)
        String digitsOnly = mobile.replaceAll("[^0-9]", "");
        // Step 2: Extract only the last 10 digits to ignore country codes reliably
        if (digitsOnly.length() >= 10) {
            return digitsOnly.substring(digitsOnly.length() - 10);
        }
        return digitsOnly;
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private void sendJsonResponse(HttpExchange exchange, int status, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}