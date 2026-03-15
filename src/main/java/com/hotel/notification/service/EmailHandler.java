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

            // Read request body
            InputStream requestBody = exchange.getRequestBody();

            Map<String, String> body =
                    mapper.readValue(requestBody, Map.class);

            String recipientEmail = body.get("email");

            EmailService emailService =
                    new EmailService(EMAIL_API_KEY, EMAIL_SENDER);

            emailService.sendEmail(
                    recipientEmail,
                    "Welcome to Hotel Booking App",
                    "Your account was created successfully."
            );

            String response = "Email sent successfully";

            exchange.sendResponseHeaders(200, response.getBytes().length);

            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();

        } catch (Exception e) {

            String response = "Failed to send email";

            exchange.sendResponseHeaders(500, response.getBytes().length);

            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();

            e.printStackTrace();
        }
    }
}