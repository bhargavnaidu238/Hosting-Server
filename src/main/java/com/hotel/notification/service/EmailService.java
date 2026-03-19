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

    public EmailService(String apiKey, String senderEmail) {
        if (apiKey == null || apiKey.isEmpty() || senderEmail == null || senderEmail.isEmpty()) {
            throw new RuntimeException("Email configuration (API Key or Sender) is missing!");
        }
        this.senderEmail = senderEmail;
        this.sendGrid = new SendGrid(apiKey);
    }

    /**
     * Sends an email using SendGrid.
     * Supports both plain text and HTML if needed.
     */
    public void sendEmail(String recipientEmail, String subject, String body) throws IOException {
        // Validation to prevent empty calls
        if (recipientEmail == null || recipientEmail.isEmpty()) {
            throw new IOException("Recipient email is null or empty");
        }

        Email from = new Email(senderEmail);
        Email to = new Email(recipientEmail);
        
        // Using "text/plain" for now. If you want to use <b> or <br> tags later, 
        // change this to "text/html"
        Content content = new Content("text/plain", body);
        Mail mail = new Mail(from, subject, to, content);

        Request request = new Request();
        try {
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build()); // This converts the Mail object to the JSON SendGrid expects

            Response response = sendGrid.api(request);

            // SendGrid success code for "Accepted" is 202
            // We check for any 2xx status code
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                System.out.println("[EmailService] Success: Email sent to " + recipientEmail + 
                                   " (Status: " + response.getStatusCode() + ")");
            } else {
                // Log the body error for easier debugging in the console
                System.err.println("[EmailService] SendGrid Error Body: " + response.getBody());
                throw new IOException("SendGrid failure. Status: " + response.getStatusCode());
            }
        } catch (IOException ex) {
            System.err.println("[EmailService] Exception sending email: " + ex.getMessage());
            throw ex;
        }
    }
}