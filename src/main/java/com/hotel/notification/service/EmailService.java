package com.hotel.notification.service;

import com.hotel.utilities.DbConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class EmailService {

    private final DbConfig dbConfig;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public EmailService(DbConfig dbConfig) {
        if (dbConfig == null) {
            throw new RuntimeException("DbConfig is required for EmailService!");
        }
        this.dbConfig = dbConfig;
    }

    //============== Sends email using Zoho Mail REST API (Port 443 - Render Free Safe) ======================
    public void sendEmail(String recipientEmail, String subject, String body) throws IOException {

        try {
            String fromEmail = dbConfig.getSmtpUsername(); // Your complete zoho.in email ID

            // Clean and escape JSON characters from the text body dynamically
            String cleanBody = body.replace("\n", "\\n").replace("\"", "\\\"");

            // Construct Zoho Mail JSON API format
            String jsonPayload = "{"
                    + "\"fromAddress\":\"" + fromEmail + "\","
                    + "\"toAddress\":\"" + recipientEmail + "\","
                    + "\"subject\":\"" + subject + "\","
                    + "\"content\":\"" + cleanBody + "\""
                    + "}";

            // Targeting Zoho India's core web gateway interface
            String apiUrl = "https://mail.zoho.in/api/v1/accounts/" + fromEmail + "/messages";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    // Uses your application credentials securely via web protocols
                    .header("Authorization", "Zoho-oauthtoken " + dbConfig.getSmtpPassword())
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Validate response logs
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                System.err.println("[EmailService] Zoho REST execution error. Response: " + response.body());
                throw new IOException("Zoho API returned bad status: " + response.statusCode());
            }

            System.out.println("[EmailService] Success: HTTP API Email sent to " + recipientEmail);

        } catch (Exception ex) {
            System.err.println("[EmailService] Error sending email: " + ex.getMessage());
            throw new IOException("HTTP API Email failed: " + ex.getMessage(), ex);
        }
    }
}