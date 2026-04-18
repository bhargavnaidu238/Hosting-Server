package com.hotel.notification.service;

import com.hotel.utilities.DbConfig;

// ✅ Jakarta Mail imports (make sure dependency is added)
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.IOException;
import java.util.Properties;

public class EmailService {

    private final DbConfig dbConfig;

    // ✅ Updated constructor (no API key needed)
    public EmailService(DbConfig dbConfig) {
        if (dbConfig == null) {
            throw new RuntimeException("DbConfig is required for EmailService!");
        }
        this.dbConfig = dbConfig;
    }

    /**
     * Sends email using Zoho SMTP
     */
    public void sendEmail(String recipientEmail, String subject, String body) throws IOException {

        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", dbConfig.getSmtpHost());
            props.put("mail.smtp.port", dbConfig.getSmtpPort());
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");

            Session session = Session.getInstance(props, new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(
                            dbConfig.getSmtpUsername(),
                            dbConfig.getSmtpPassword()
                    );
                }
            });

            Message message = new MimeMessage(session);

            // ✅ Sender with name (fallback safe)
            if (dbConfig.getSenderName() != null && !dbConfig.getSenderName().isEmpty()) {
                message.setFrom(new InternetAddress(
                        dbConfig.getSenderEmail(),
                        dbConfig.getSenderName()
                ));
            } else {
                message.setFrom(new InternetAddress(dbConfig.getSenderEmail()));
            }

            message.setRecipients(Message.RecipientType.TO,
                    InternetAddress.parse(recipientEmail));

            message.setSubject(subject);

            // You can switch to "text/html" later if needed
            message.setText(body);

            Transport.send(message);

            System.out.println("[EmailService] Success: Email sent to " + recipientEmail);

        } catch (Exception ex) {
            System.err.println("[EmailService] Error sending email: " + ex.getMessage());
            throw new IOException("SMTP Email failed: " + ex.getMessage(), ex);
        }
    }
}