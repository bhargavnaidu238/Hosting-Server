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
                        String checkIn = rs.getString("check_in_date");
                        String checkOut = rs.getString("check_out_date");
                        if (checkIn != null && !checkIn.isEmpty()) checkInDate = LocalDate.parse(checkIn.split(" ")[0]);
                        if (checkOut != null && !checkOut.isEmpty()) checkOutDate = LocalDate.parse(checkOut.split(" ")[0]);
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
                String bookingSql = "SELECT partner_id, guest_name, check_in_date, check_out_date, room_type, amount_paid_online" +
                                  "FROM bookings_info WHERE booking_id = ?";
                
                String partnerId = "";
                String checkIn = "", checkOut = "", custName = "", room = "", total = "";

                try (PreparedStatement ps = customerConn.prepareStatement(bookingSql)) {
                    ps.setString(1, bookingId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        partnerId = rs.getString("partner_id");
                        custName = rs.getString("guest_name");
                        checkIn = rs.getString("check_in_date");
                        checkOut = rs.getString("check_out_date");
                        room = rs.getString("room_type");
                        total = rs.getString("amount_paid_online");
                    }
                }

                // 2. Fetch Partner Contact Info
                if (!partnerId.isEmpty()) {
                    String partnerSql = "SELECT partner_name, email FROM partner_data WHERE partner_id = ?";
                    try (PreparedStatement ps2 = partnerConn.prepareStatement(partnerSql)) {
                        ps2.setString(1, partnerId);
                        ResultSet rs2 = ps2.executeQuery();
                        if (rs2.next()) {
                            String pName = rs2.getString("partner_name");
                            String pEmail = rs2.getString("email");

                            EmailService emailService = new EmailService(dbConfig.getEmailApiKey(), dbConfig.getSenderEmail());
                            String subject = "Booking Update: ID #" + bookingId + " is now " + status;
                            
                            StringBuilder body = new StringBuilder();
                            body.append("Hello ").append(pName).append(",\n\n");
                            body.append("There is an update regarding a booking at your property:\n\n");
                            body.append("Booking ID: ").append(bookingId).append("\n");
                            body.append("Customer: ").append(custName).append("\n");
                            body.append("Room Type: ").append(room).append("\n");
                            body.append("Check-in: ").append(checkIn).append("\n");
                            body.append("Check-out: ").append(checkOut).append("\n");
                            body.append("Total Amount: ₹").append(total).append("\n");
                            body.append("Current Status: ").append(status).append("\n\n");

                            if ("PENDING".equalsIgnoreCase(status)) {
                                body.append("ACTION REQUIRED: This booking is currently PENDING. Please log in to the Partner Portal and CONFIRM this booking based on your current room availability.\n\n");
                            }

                            body.append("Regards,\nHotel Management Team");

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