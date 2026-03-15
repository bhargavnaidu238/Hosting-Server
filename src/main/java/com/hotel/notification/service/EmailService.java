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

    private final String apiKey;
    private final String senderEmail;

    public EmailService(String apiKey, String senderEmail) {

        this.apiKey = apiKey;
        this.senderEmail = senderEmail;

        if (apiKey == null || senderEmail == null) {
            throw new RuntimeException("Email configuration is missing!");
        }
    }

    /*
     * ========================================
     * GENERIC EMAIL SENDER (USED BY EmailHandler)
     * ========================================
     */
    public void sendEmail(String recipientEmail, String subject, String body)
            throws IOException {

        Email from = new Email(senderEmail);
        Email to = new Email(recipientEmail);

        Content content = new Content("text/plain", body);

        Mail mail = new Mail(from, subject, to, content);

        SendGrid sendGrid = new SendGrid(apiKey);

        Request request = new Request();
        request.setMethod(Method.POST);
        request.setEndpoint("mail/send");
        request.setBody(mail.build());

        Response response = sendGrid.api(request);

        System.out.println("====================================");
        System.out.println("Email sent to: " + recipientEmail);
        System.out.println("Subject: " + subject);
        System.out.println("SendGrid Status Code: " + response.getStatusCode());
        System.out.println("====================================");
    }
}