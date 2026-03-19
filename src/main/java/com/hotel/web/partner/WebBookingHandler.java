package com.hotel.web.partner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.utilities.DbConfig;
import com.hotel.notification.service.EmailService;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

public class WebBookingHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public WebBookingHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        if (method.equalsIgnoreCase("OPTIONS")) {
            addCORSHeaders(exchange);
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();

        if (path.endsWith("/webgetPartnerBookings")) {
            handleGetBookings(exchange);
        } else if (path.endsWith("/webcancelBooking")) {
            handleCancelBooking(exchange);
        } else if (path.endsWith("/webupdateBookingStatus")) {
            handleUpdateBookingStatus(exchange);
        } else {
            exchange.sendResponseHeaders(404, -1);
        }
    }

    private void addCORSHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private Map<String, String> parsePostBody(HttpExchange exchange) throws IOException {
        Map<String, String> map = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            if (!sb.toString().isEmpty()) {
                for (String pair : sb.toString().split("&")) {
                    String[] parts = pair.split("=", 2);
                    if (parts.length == 2) {
                        map.put(URLDecoder.decode(parts[0], "UTF-8").trim(), URLDecoder.decode(parts[1], "UTF-8").trim());
                    }
                }
            }
        }
        return map;
    }

    private String getQueryParam(HttpExchange exchange, String key) throws UnsupportedEncodingException {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return "";
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length == 2 && pair[0].equalsIgnoreCase(key)) {
                return URLDecoder.decode(pair[1], "UTF-8").trim();
            }
        }
        return "";
    }

    private void handleGetBookings(HttpExchange exchange) throws IOException {
        addCORSHeaders(exchange);
        String partnerId = getQueryParam(exchange, "partnerId");
        List<Map<String, String>> bookings = new ArrayList<>();

        String sql = "SELECT * FROM bookings_info WHERE partner_id = ? ORDER BY booking_id DESC";

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, partnerId);
            try (ResultSet rs = stmt.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        String col = meta.getColumnName(i);
                        String val = rs.getString(i);
                        row.put(col, val != null ? val : "");
                    }
                    bookings.add(row);
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }

        String json = new ObjectMapper().writeValueAsString(bookings);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, json.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
    }

    private void handleCancelBooking(HttpExchange exchange) throws IOException {
        addCORSHeaders(exchange);
        Map<String, String> params = parsePostBody(exchange);
        String bookingId = params.getOrDefault("bookingId", "");
        boolean success = false;

        if (!bookingId.isEmpty()) {
            String sql = "UPDATE bookings_info SET booking_status = 'CANCELLED'::booking_status_enum WHERE booking_id = ?";
            try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, bookingId);
                success = stmt.executeUpdate() > 0;
                if (success) {
                    triggerBookingNotification(bookingId, "CANCELLED");
                }
            } catch (SQLException e) { e.printStackTrace(); }
        }

        String response = "{\"status\":\"" + (success ? "success" : "failed") + "\"}";
        exchange.sendResponseHeaders(200, response.length());
        try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes()); }
    }

    private void handleUpdateBookingStatus(HttpExchange exchange) throws IOException {
        addCORSHeaders(exchange);
        Map<String, String> params = parsePostBody(exchange);
        if (params.isEmpty()) {
            params.put("bookingId", getQueryParam(exchange, "bookingId"));
            params.put("status", getQueryParam(exchange, "status"));
        }

        String bookingId = params.getOrDefault("bookingId", "").trim();
        String newStatus = params.getOrDefault("status", "").trim().toUpperCase();

        boolean success = false;
        String message = "";

        if (!bookingId.isEmpty() && !newStatus.isEmpty()) {
            String fetchSql = "SELECT booking_status, check_in_date, check_out_date FROM bookings_info WHERE booking_id = ?";
            String updateSql = "UPDATE bookings_info SET booking_status = ?::booking_status_enum WHERE booking_id = ?";

            try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
                 PreparedStatement fetchStmt = conn.prepareStatement(fetchSql)) {

                fetchStmt.setString(1, bookingId);
                String currentStatus = "";
                LocalDate checkInDate = null;
                LocalDate checkOutDate = null;

                try (ResultSet rs = fetchStmt.executeQuery()) {
                    if (rs.next()) {
                        currentStatus = rs.getString("booking_status").toUpperCase();
                        Date checkIn = rs.getDate("check_in_date");
                        Date checkOut = rs.getDate("check_out_date");
                        if (checkIn != null) checkInDate = checkIn.toLocalDate();
                        if (checkOut != null) checkOutDate = checkOut.toLocalDate();
                    }
                }

                LocalDate today = LocalDate.now();
                boolean allowed = false;

                switch (newStatus) {
                    case "CONFIRMED": 
                        allowed = "PENDING".equals(currentStatus); 
                        break;
                    case "CANCELLED": 
                        allowed = "PENDING".equals(currentStatus) || "CONFIRMED".equals(currentStatus); 
                        break;
                    case "CHECKED_IN":
                        allowed = ("PENDING".equals(currentStatus) && checkInDate != null && checkInDate.equals(today)) ||
                                  "CONFIRMED".equals(currentStatus);
                        break;
                    case "CHECKED_OUT":
                        allowed = ("CONFIRMED".equals(currentStatus) || "CHECKED_IN".equals(currentStatus)) 
                                  && checkOutDate != null && !checkOutDate.isAfter(today);
                        break;
                    case "COMPLETED":
                        allowed = "CONFIRMED".equals(currentStatus) || 
                                  "CHECKED_IN".equals(currentStatus) || 
                                  "CHECKED_OUT".equals(currentStatus);
                        break;
                }

                if (allowed) {
                    try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                        updateStmt.setString(1, newStatus);
                        updateStmt.setString(2, bookingId);
                        success = updateStmt.executeUpdate() > 0;
                        if (success) {
                            message = "Status updated successfully";
                            triggerBookingNotification(bookingId, newStatus);
                        } else {
                            message = "Update failed";
                        }
                    }
                } else {
                    message = "Action " + newStatus + " not allowed for current status: " + currentStatus;
                }
            } catch (SQLException e) {
                e.printStackTrace();
                message = "Database error: " + e.getMessage();
            }
        }

        String response = "{\"status\":\"" + (success ? "success" : "failed") + "\",\"message\":\"" + message + "\"}";
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes(StandardCharsets.UTF_8)); }
    }

    private void triggerBookingNotification(String bookingId, String status) {
        new Thread(() -> {
            try (Connection customerConn = dbConfig.getCustomerDataSource().getConnection();
                 Connection partnerConn = dbConfig.getPartnerDataSource().getConnection()) {

                // 1. Fetch Booking Details
                String bookingSql = "SELECT partner_id, guest_name, check_in_date, check_out_date, room_type, amount_paid_online " +
                                  "FROM bookings_info WHERE booking_id = ?";
                
                String partnerId = "", gName = "", cin = "", cout = "", rType = "", amt = "";

                try (PreparedStatement ps = customerConn.prepareStatement(bookingSql)) {
                    ps.setString(1, bookingId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        partnerId = rs.getString("partner_id");
                        gName = rs.getString("guest_name");
                        cin = rs.getString("check_in_date");
                        cout = rs.getString("check_out_date");
                        rType = rs.getString("room_type");
                        amt = rs.getString("amount_paid_online");
                    }
                }

                // 2. Fetch Partner Contact Info and Send Email
                if (partnerId != null && !partnerId.isEmpty()) {
                    String partnerSql = "SELECT partner_name, email FROM partner_data WHERE partner_id = ?";
                    try (PreparedStatement ps2 = partnerConn.prepareStatement(partnerSql)) {
                        ps2.setString(1, partnerId);
                        ResultSet rs2 = ps2.executeQuery();
                        if (rs2.next()) {
                            String pName = rs2.getString("partner_name");
                            String pEmail = rs2.getString("email");

                            EmailService emailService = new EmailService(dbConfig.getEmailApiKey(), dbConfig.getSenderEmail());
                            String subject = "Booking Notification: ID #" + bookingId + " is " + status;
                            
                            StringBuilder body = new StringBuilder();
                            body.append("Hello ").append(pName).append(",\n\n");
                            body.append("A booking status at your property has been updated to: ").append(status).append("\n\n");
                            body.append("--- Booking Details ---\n");
                            body.append("Booking ID: ").append(bookingId).append("\n");
                            body.append("Guest Name: ").append(gName).append("\n");
                            body.append("Room Type: ").append(rType).append("\n");
                            body.append("Check-in: ").append(cin).append("\n");
                            body.append("Check-out: ").append(cout).append("\n");
                            body.append("Amount Paid: ₹").append(amt != null ? amt : "0.00").append("\n\n");

                            if ("PENDING".equalsIgnoreCase(status)) {
                                body.append("ACTION REQUIRED: This booking is currently PENDING. Please verify availability and CONFIRM this booking in the Partner Portal to avoid guest inconvenience.\n\n");
                            }

                            body.append("Regards,\nHotel Operations Team");

                            emailService.sendEmail(pEmail, subject, body.toString());
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[BookingNotificationError] Failed to send email: " + e.getMessage());
            }
        }).start();
    }
}