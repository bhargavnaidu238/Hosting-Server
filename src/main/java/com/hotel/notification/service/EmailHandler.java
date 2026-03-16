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
        // 1. CORS Headers
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
                // FOR REGISTRATION: Check if user already exists
                if (checkUserExists(email)) {
                    sendResponse(exchange, 409, "{\"status\":\"error\",\"message\":\"This email is already registered. Please login instead.\"}");
                    return;
                }
                handleSendOtp(exchange, email, "Verification Code", "Your verification OTP is: ");
            } 
            else if ("forgot_password_otp".equals(type)) {
                // FOR FORGOT PASSWORD: Check if email exists in system
                if (!checkUserExists(email)) {
                    sendResponse(exchange, 404, "{\"status\":\"error\",\"message\":\"This email is not registered with us.\"}");
                    return;
                }
                handleSendOtp(exchange, email, "Reset Your Password", "You requested to reset your password. Your OTP is: ");
            } 
            else if ("verify_otp".equals(type)) {
                handleVerifyOtp(exchange, email, body.getOrDefault("otp", ""));
            } 
            else {
                sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid type\"}");
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    /**
     * Helper to verify if an email exists in the partner_data table
     */
    private boolean checkUserExists(String email) throws SQLException {
        String query = "SELECT 1 FROM partner_data WHERE LOWER(email) = ?";
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, email);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void handleSendOtp(HttpExchange exchange, String email, String subject, String prefix) throws Exception {
        String otp = String.valueOf(new Random().nextInt(900000) + 100000);
        Timestamp expiry = new Timestamp(System.currentTimeMillis() + (5 * 60 * 1000)); // 5 mins

        // PostgreSQL Upsert for OTP management
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection()) {
            String query = "INSERT INTO email_verification_otp (email, otp_code, otp_expiry, attempts) " +
                           "VALUES (?, ?, ?, 0) " +
                           "ON CONFLICT (email) DO UPDATE SET " +
                           "otp_code = EXCLUDED.otp_code, " +
                           "otp_expiry = EXCLUDED.otp_expiry, " +
                           "attempts = 0";

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, email);
                stmt.setString(2, otp);
                stmt.setTimestamp(3, expiry);
                stmt.executeUpdate();
            }
        }

        // Trigger the Email
        EmailService emailService = new EmailService(dbConfig.getEmailApiKey(), dbConfig.getSenderEmail());
        String body = prefix + otp + "\n\nThis code expires in 5 minutes.\n\nRegards,\nHotel Booking Team";
        emailService.sendEmail(email, subject, body);

        sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"OTP sent to email\"}");
    }

    private void handleVerifyOtp(HttpExchange exchange, String email, String userOtp) throws Exception {
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection()) {
            String query = "SELECT otp_code, otp_expiry FROM email_verification_otp WHERE email = ?";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, email);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String storedOtp = rs.getString("otp_code");
                        Timestamp expiry = rs.getTimestamp("otp_expiry");

                        if (expiry.before(new Timestamp(System.currentTimeMillis()))) {
                            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"OTP expired\"}");
                        } else if (storedOtp.equals(userOtp)) {
                            sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"OTP verified\"}");
                        } else {
                            sendResponse(exchange, 401, "{\"status\":\"error\",\"message\":\"Invalid OTP\"}");
                        }
                    } else {
                        sendResponse(exchange, 404, "{\"status\":\"error\",\"message\":\"No OTP session found\"}");
                    }
                }
            }
        }
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