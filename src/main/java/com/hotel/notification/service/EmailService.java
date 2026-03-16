package com.hotel.notification.service;

import java.io.IOException;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;

public class EmailService {

    private final String senderEmail;
    private final SendGrid sendGrid;
	private String apiKey;

    public EmailService(String apiKey, String senderEmail) {
        if (apiKey == null || apiKey.isEmpty() || senderEmail == null || senderEmail.isEmpty()) {
            throw new RuntimeException("Email configuration (API Key or Sender) is missing!");
        }
        this.apiKey = apiKey; // Keeping reference if needed
        this.senderEmail = senderEmail;
        // Initialize SendGrid client once
        this.sendGrid = new SendGrid(apiKey);
    }

    /**
     * Sends an email using SendGrid.
     * Supports both plain text and HTML if needed.
     */
    public void sendEmail(String recipientEmail, String subject, String body) throws IOException {
        Email from = new Email(senderEmail);
        Email to = new Email(recipientEmail);
        
        // Use text/html if you want to send styled emails later, otherwise text/plain is fine.
        Content content = new Content("text/plain", body);
        Mail mail = new Mail(from, subject, to, content);

        Request request = new Request();
        try {
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGrid.api(request);

            // SendGrid success code for "Accepted" is 202
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                System.out.println("[EmailService] Success: Email sent to " + recipientEmail + 
                                   " (Status: " + response.getStatusCode() + ")");
            } else {
                // IMPORTANT: Throwing an exception so the Handler knows it failed
                throw new IOException("SendGrid failure. Status: " + response.getStatusCode() + 
                                      " Body: " + response.getBody());
            }
        } catch (IOException ex) {
            System.err.println("[EmailService] Error sending email: " + ex.getMessage());
            throw ex; // Rethrow to let the caller handle the failure logic
        }
    }
}