package com.hotel.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;

public class EmailHandler implements HttpHandler {

	    private final DbConfig dbConfig;
	    private final ObjectMapper mapper = new ObjectMapper();

	    // Razorpay credentials
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
        
        String userEmail = EMAIL_SENDER;

        EmailService emailService = new EmailService();

        try {

            emailService.sendEmail(
                    userEmail,
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
