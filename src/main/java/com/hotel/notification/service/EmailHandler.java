package com.hotel.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.*;
import java.util.Map;
import java.util.Random;

public class EmailHandler implements HttpHandler {

    private final DbConfig dbConfig;
    private final ObjectMapper mapper = new ObjectMapper();

    public EmailHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORS Headers
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendResponse(exchange, 405, "{\"status\":\"error\",\"message\":\"Method not allowed\"}");
            return;
        }

        try (InputStream is = exchange.getRequestBody()) {
            Map<String, String> body = mapper.readValue(is, Map.class);
            String type = body.getOrDefault("type", "").toLowerCase();
            String email = body.getOrDefault("email", "").trim().toLowerCase();

            if ("send_otp".equals(type)) {
                if (checkUserExists(email)) {
                    sendResponse(exchange, 409, "{\"status\":\"error\",\"message\":\"Email already exists. Please login.\"}");
                    return;
                }
                handleSendOtp(exchange, email, "Verification Code", "Your verification OTP is: ");
            } 
            else if ("forgot_password_verify".equals(type)) {
                String inputMobile = normalizeMobile(body.getOrDefault("mobile", ""));
                if (verifyUserAndMobile(email, inputMobile)) {
                    // Logic: If user and mobile match, trigger the OTP sending process
                    handleSendOtp(exchange, email, "Reset Your Password", "You requested to reset your password. Your OTP is: ");
                } else {
                    sendResponse(exchange, 404, "{\"status\":\"error\",\"message\":\"Email or mobile number not matching\"}");
                }
            }
            else if ("verify_otp".equals(type)) {
                String otp = body.getOrDefault("otp", "").trim();
                handleVerifyOtp(exchange, email, otp);
            } 
            else {
                sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid type\"}");
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"Internal Server Error\"}");
        }
    }

    /**
     * Verifies if the email exists and the normalized mobile number matches.
     */
    private boolean verifyUserAndMobile(String email, String inputMobile) throws SQLException {
        String query = "SELECT mobile_number FROM user_info WHERE LOWER(user_email) = ? AND status = 'Active'";
        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, email.toLowerCase());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String dbMobile = normalizeMobile(rs.getString("mobile_number"));
                    return inputMobile.equals(dbMobile);
                }
            }
        }
        return false;
    }

    private boolean checkUserExists(String email) throws SQLException {
        String query = "SELECT 1 FROM user_info WHERE LOWER(user_email) = ?";
        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, email.toLowerCase());
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void handleSendOtp(HttpExchange exchange, String email, String subject, String prefix) throws Exception {
        String otp = String.valueOf(new Random().nextInt(900000) + 100000);
        Timestamp expiry = new Timestamp(System.currentTimeMillis() + (2 * 60 * 1000)); 

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection()) {
            String query = "INSERT INTO email_verification_otp (email, otp_code, otp_expiry, attempts) " +
                           "VALUES (?, ?, ?, 1) " +
                           "ON CONFLICT (email) DO UPDATE SET " +
                           "otp_code = EXCLUDED.otp_code, " +
                           "otp_expiry = EXCLUDED.otp_expiry, " +
                           "attempts = email_verification_otp.attempts + 1";

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, email.toLowerCase());
                stmt.setString(2, otp);
                stmt.setTimestamp(3, expiry);
                stmt.executeUpdate();
            }
        }

        EmailService emailService = new EmailService(dbConfig.getEmailApiKey(), dbConfig.getSenderEmail());
        String bodyText = prefix + otp + "\n\nThis code expires in 2 minutes.\n\nRegards,\nHotel Booking Team";
        emailService.sendEmail(email, subject, bodyText);

        sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"OTP sent to email\"}");
    }

    private void handleVerifyOtp(HttpExchange exchange, String email, String userOtp) throws Exception {
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection()) {
            String query = "SELECT otp_code, otp_expiry, attempts FROM email_verification_otp WHERE LOWER(email) = ?";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, email.toLowerCase());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String storedOtp = rs.getString("otp_code").trim();
                        Timestamp expiry = rs.getTimestamp("otp_expiry");
                        int attempts = rs.getInt("attempts");

                        if (attempts > 10) {
                            sendResponse(exchange, 429, "{\"status\":\"error\",\"message\":\"Max attempts reached.\"}");
                        } else if (expiry.before(new Timestamp(System.currentTimeMillis()))) {
                            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"OTP expired\"}");
                        } else if (storedOtp.equals(userOtp)) {
                            sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"OTP verified\"}");
                        } else {
                            sendResponse(exchange, 401, "{\"status\":\"error\",\"message\":\"Enter Wrong OTP Please try again.\"}");
                        }
                    } else {
                        sendResponse(exchange, 404, "{\"status\":\"error\",\"message\":\"No OTP session found\"}");
                    }
                }
            }
        }
    }

    private String normalizeMobile(String mobile) {
        if (mobile == null) return "";
        String digitsOnly = mobile.replaceAll("[^0-9]", "");
        if (digitsOnly.length() >= 10) {
            return digitsOnly.substring(digitsOnly.length() - 10);
        }
        return digitsOnly;
    }

    private void sendResponse(HttpExchange exchange, int status, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = response.getBytes();
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}