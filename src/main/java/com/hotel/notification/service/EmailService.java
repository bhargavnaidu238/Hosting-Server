package com.hotel.notification.service;

import com.hotel.utilities.DbConfig;

// ✅ Jakarta Mail imports
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.IOException;
import java.util.Properties;

public class EmailService {

    private final DbConfig dbConfig;

    public EmailService(DbConfig dbConfig) {
        if (dbConfig == null) {
            throw new RuntimeException("DbConfig is required for EmailService!");
        }
        this.dbConfig = dbConfig;
    }

    //============== Sends email using Zoho SMTP (SSL Port 465) ======================
    public void sendEmail(String recipientEmail, String subject, String body) throws IOException {

        try {
            Properties props = new Properties();
            
            // Hardcoding the host/port ensures it bypasses incorrect configurations, 
            // or you can keep using dbConfig if your Render environment variables are exactly "smtp.zoho.in" and "465"
            props.put("mail.smtp.host", "smtp.zoho.in"); 
            props.put("mail.smtp.port", "465");
            props.put("mail.smtp.auth", "true");
            
            // ✅ CRITICAL CHANGES FOR ZOHO INDIA SSL REQUIREMENT
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.port", "465");
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            props.put("mail.smtp.socketFactory.fallback", "false");

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(
                            dbConfig.getSmtpUsername(), // Must be your complete zoho.in email ID
                            dbConfig.getSmtpPassword()  // Must be your generated 16-character App Password
                    );
                }
            });

            Message message = new MimeMessage(session);

            // Safe fallback logic for Sender data
            String senderEmail = dbConfig.getSenderEmail() != null ? dbConfig.getSenderEmail() : dbConfig.getSmtpUsername();
            
            if (dbConfig.getSenderName() != null && !dbConfig.getSenderName().isEmpty()) {
                message.setFrom(new InternetAddress(senderEmail, dbConfig.getSenderName()));
            } else {
                message.setFrom(new InternetAddress(senderEmail));
            }

            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientEmail));
            message.setSubject(subject);
            message.setText(body);

            Transport.send(message);

            System.out.println("[EmailService] Success: Email sent to " + recipientEmail);

        } catch (Exception ex) {
            System.err.println("[EmailService] Error sending email: " + ex.getMessage());
            throw new IOException("SMTP Email failed: " + ex.getMessage(), ex);
        }
    }
}