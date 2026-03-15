package com.hotel.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

public class EmailHandler implements HttpHandler {

    private final DbConfig dbConfig;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String EMAIL_API_KEY;
    private final String EMAIL_SENDER;

    public EmailHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
        this.EMAIL_API_KEY = dbConfig.getEmailApiKey();
        this.EMAIL_SENDER = dbConfig.getSenderEmail();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try {

            InputStream requestBody = exchange.getRequestBody();

            Map<String, String> body =
                    mapper.readValue(requestBody, Map.class);

            String email = body.getOrDefault("email", "").trim();
            String type = body.getOrDefault("type", "").trim().toLowerCase();
            String otp = body.getOrDefault("otp", "").trim();
            String partnerName = body.getOrDefault("partnerName", "Partner");

            if (email.isEmpty()) {
                sendResponse(exchange, 400, "Email is required");
                return;
            }

            EmailService emailService =
                    new EmailService(EMAIL_API_KEY, EMAIL_SENDER);

            String subject;
            String message;

            /*
             * ========================================
             * OTP EMAIL TEMPLATE
             * ========================================
             */
            if ("otp".equals(type)) {

                subject = "Email Verification OTP - Hotel Booking Portal";

                message =
                        "Hello,\n\n" +
                        "Your Email Verification OTP is: " + otp + "\n\n" +
                        "This OTP is valid for 5 minutes.\n\n" +
                        "If you did not request this verification, please ignore this email.\n\n" +
                        "Regards,\n" +
                        "Hotel Booking Team";

                emailService.sendEmail(email, subject, message);
            }

            /*
             * ========================================
             * WELCOME EMAIL TEMPLATE
             * ========================================
             */
            else if ("welcome".equals(type)) {

                subject = "Welcome to Hotel Booking Partner Portal";

                message =
                        "Hello " + partnerName + ",\n\n" +
                        "Welcome to the Hotel Booking Partner Portal!\n\n" +
                        "Your registration has been successfully completed.\n\n" +
                        "You can now login and start managing your hotel listings.\n\n" +
                        "We are excited to have you onboard.\n\n" +
                        "Regards,\n" +
                        "Hotel Booking Team";

                emailService.sendEmail(email, subject, message);
            }

            /*
             * ========================================
             * GENERIC EMAIL (fallback)
             * ========================================
             */
            else {

                subject = "Hotel Booking Notification";

                message =
                        "Hello,\n\n" +
                        "This is a notification from Hotel Booking Portal.\n\n" +
                        "Regards,\n" +
                        "Hotel Booking Team";

                emailService.sendEmail(email, subject, message);
            }

            sendResponse(exchange, 200, "Email sent successfully");

        } catch (Exception e) {

            e.printStackTrace();

            sendResponse(exchange, 500, "Failed to send email");
        }
    }

    private void sendResponse(HttpExchange exchange, int status, String message)
            throws IOException {

        byte[] response = message.getBytes();

        exchange.sendResponseHeaders(status, response.length);

        OutputStream os = exchange.getResponseBody();
        os.write(response);
        os.close();
    }
}