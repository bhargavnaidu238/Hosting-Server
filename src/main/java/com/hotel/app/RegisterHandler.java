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
            String firstName = json.optString("firstName", "User").trim();
            String lastName = json.optString("lastName", "").trim();
            String gender = json.optString("gender", null);
            String mobile = json.optString("mobile", null);
            String address = json.optString("address", null);
            String rawPassword = json.getString("password");
            String consent = json.optString("consent", "No");
            
            // New: Optional Referral Code provided by the new user
            String referredByCode = json.optString("referred_by", "").trim().toUpperCase();

            if (!consent.equalsIgnoreCase("Yes") && !consent.equalsIgnoreCase("No")) {
                sendResponse(exchange, 400, "Consent must be Yes or No");
                return;
            }

            String hashedPassword = PasswordUtil.hashPassword(rawPassword);
            String finalUserId;
            String newUserReferralCode;

            try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {
                conn.setAutoCommit(false);

                // 1. Check if email exists
                try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM user_info WHERE LOWER(user_email) = ?")) {
                    ps.setString(1, email);
                    if (ps.executeQuery().next()) {
                        sendResponse(exchange, 409, "Email already exists");
                        return;
                    }
                }

                // 2. Validate provided Referral Code (if any)
                String referrerUserId = null;
                if (!referredByCode.isEmpty()) {
                    String checkRefSql = "SELECT user_id FROM user_info WHERE referral_code = ?";
                    try (PreparedStatement ps = conn.prepareStatement(checkRefSql)) {
                        ps.setString(1, referredByCode);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            referrerUserId = rs.getString("user_id");
                        } else {
                            sendResponse(exchange, 400, "Invalid Referral Code");
                            return;
                        }
                    }
                }

                // 3. Generate user_id (CR prefix logic)
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

                // 4. Generate Unique Referral Code for the new user
                // Format: First 3 of Name + Last 4 of ID (e.g., GAU7601)
                String namePart = firstName.length() >= 3 ? firstName.substring(0, 3).toUpperCase() : (firstName + "X").substring(0, 3).toUpperCase();
                String idPart = finalUserId.substring(finalUserId.length() - 4);
                newUserReferralCode = "HB-" + namePart + idPart;

                // 5. Insert user into user_info
                String insertSql = """
                    INSERT INTO user_info 
                    (user_id, user_email, password, first_name, last_name, 
                     gender, mobile_number, address, consent, referral_code, signup_referral_code) 
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::yes_no_enum, ?, ?)
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
                    ps.setString(10, newUserReferralCode);
                    ps.setString(11, referredByCode.isEmpty() ? null : referredByCode);
                    ps.executeUpdate();
                }

                // 6. Create wallet with Joining Bonus
                String walletSql = "INSERT INTO wallets (wallet_id, user_id, balance, status) VALUES (?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(walletSql)) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, finalUserId);
                    ps.setBigDecimal(3, new java.math.BigDecimal("200.00")); // Welcome Bonus
                    ps.setString(4, "Active");
                    ps.executeUpdate();
                }

                // 7. If referred by someone, link them in the referrals table
                if (referrerUserId != null) {
                    String refLinkSql = "INSERT INTO referrals (referrer_id, referee_id, referral_code) VALUES (?, ?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(refLinkSql)) {
                        ps.setString(1, referrerUserId);
                        ps.setString(2, finalUserId);
                        ps.setString(3, referredByCode);
                        ps.executeUpdate();
                    }
                }

                conn.commit();
            }

            // OTP Cleanup Logic
            try (Connection otpConn = dbConfig.getPartnerDataSource().getConnection()) {
                String deleteOtp = "DELETE FROM email_verification_otp WHERE LOWER(email) = ?";
                try (PreparedStatement deleteStmt = otpConn.prepareStatement(deleteOtp)) {
                    deleteStmt.setString(1, email);
                    deleteStmt.executeUpdate();
                }
            } catch (SQLException e) {
                System.err.println("OTP Cleanup Warning: " + e.getMessage());
            }

            // Async Welcome Email
            String fullName = (firstName + " " + lastName).trim();
            final String referralCodeForEmail = newUserReferralCode;
            new Thread(() -> {
                try {
                    EmailService emailService = new EmailService(dbConfig);
                    String subject = "Welcome to Hotel Booking";
                    String welcomeBody = "Hello " + fullName + ",\n\n" +
                                         "Your registration is successful.\n" +
                                         "Your User ID: " + finalUserId + "\n" +
                                         "Your Personal Referral Code: " + referralCodeForEmail + "\n\n" +
                                         "Share your code with friends to earn rewards!\n\n" +
                                         "Regards,\nTeam Hotel Booking";
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
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}