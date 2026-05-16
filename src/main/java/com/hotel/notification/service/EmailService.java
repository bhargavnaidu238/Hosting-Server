package com.hotel.notification.service;

import com.hotel.utilities.DbConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class EmailService {

    private final DbConfig dbConfig;
    private final HttpClient httpClient;

    public EmailService(DbConfig dbConfig) {
        if (dbConfig == null) {
            throw new RuntimeException("DbConfig is required for EmailService!");
        }
        this.dbConfig = dbConfig;
        // Native Java HTTP client (Thread-safe and reusable)
        this.httpClient = HttpClient.newHttpClient();
    }

    // ============== Sends email using Zoho REST API (Bypasses Render SMTP Block) ======================
    public void sendEmail(String recipientEmail, String subject, String body) throws IOException {
        try {
            // dbConfig.getSmtpHost() now returns: "https://mail.zoho.in/api/accounts/"
            // dbConfig.getSenderEmail() (or Username) provides your account email profile identifier
            String url = dbConfig.getSmtpHost() + dbConfig.getSenderEmail() + "/messages";

            // Build a clean, escaped JSON string manually to avoid parsing issues
            String jsonPayload = String.format(
                "{" +
                "\"fromAddress\":\"%s\"," +
                "\"toAddress\":\"%s\"," +
                "\"subject\":\"%s\"," +
                "\"content\":\"%s\"" +
                "}",
                dbConfig.getSenderEmail(),
                recipientEmail,
                escapeJson(subject),
                escapeJson(body)
            );

            // Constructing the API Request over Port 443 (HTTPS)
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    // Zoho-encauthtoken expects your app password/token to authenticate the REST call
                    .header("Authorization", "Zoho-encauthtoken " + dbConfig.getSmtpPassword())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            // Fire the request directly to Zoho over open web traffic lanes
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Zoho API returns a 200 OK status code on successful queueing
            if (response.statusCode() == 200 || response.statusCode() == 201) {
                System.out.println("[EmailService] Success: API Email sent to " + recipientEmail);
            } else {
                System.err.println("[EmailService] Zoho API Failed with Status Code: " + response.statusCode());
                System.err.println("[EmailService] Error Raw Response: " + response.body());
                throw new IOException("Zoho API returned bad status code: " + response.statusCode());
            }

        } catch (Exception ex) {
            System.err.println("[EmailService] Error sending via API: " + ex.getMessage());
            throw new IOException("API Email dispatch failed: " + ex.getMessage(), ex);
        }
    }

    // Simple sanitization method to prevent text quotes or newlines from breaking your JSON string
    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
    }
}