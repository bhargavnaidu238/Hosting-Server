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

    public EmailService() {
        // Read from environment variables (Render)
        this.apiKey = System.getenv("EMAIL_NOTIFICATION_API");
        this.senderEmail = System.getenv("SENDER_MAIL");

        if (apiKey == null || senderEmail == null) {
            throw new RuntimeException("Email environment variables are not configured!");
        }
    }

    public void sendEmail(String toEmail, String subject, String body) throws IOException {

        Email from = new Email(senderEmail);
        Email to = new Email(toEmail);

        Content content = new Content("text/plain", body);

        Mail mail = new Mail(from, subject, to, content);

        SendGrid sendGrid = new SendGrid(apiKey);

        Request request = new Request();
        request.setMethod(Method.POST);
        request.setEndpoint("mail/send");
        request.setBody(mail.build());

        Response response = sendGrid.api(request);

        System.out.println("Email Status Code: " + response.getStatusCode());
    }
}
