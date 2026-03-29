package com.hotel.web.finance;

import com.hotel.utilities.DbConfig;
import com.hotel.notification.service.EmailService;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class RequestPayoutHandler implements HttpHandler {

    private final DbConfig dbConfig;
    private static final double MIN_WITHDRAWAL = 5000.0;
    private static final double FALLBACK_COMMISSION_PERCENT = 15.0;

    public RequestPayoutHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String body = readBody(exchange);
        Map<String, String> params = parseForm(body);
        String partnerId = params.get("partner_id");
        double requestedAmount = Double.parseDouble(params.getOrDefault("amount", "0"));
        String comments = params.getOrDefault("comments", "User Requested Payout").trim();

        if (partnerId == null || partnerId.isEmpty()) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"partner_id required\"}");
            return;
        }

        Connection finConn = null;
        Connection bookConn = null;

        try {
            bookConn = dbConfig.getCustomerDataSource().getConnection();
            double totalRevenue = computeTotalRevenueFromBookings(bookConn, partnerId);

            finConn = dbConfig.getPartnerDataSource().getConnection();
            finConn.setAutoCommit(false);

            // 1. Fetch Finance Row with Lock
            double commPct, netRev;
            String bankAcc, bankName;
            
            String selectSQL = "SELECT Commission_Percentage, Bank_Name, Account_Number " +
                             "FROM Partner_Finance WHERE Partner_ID=? FOR UPDATE";

            try (PreparedStatement ps = finConn.prepareStatement(selectSQL)) {
                ps.setString(1, partnerId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) {
                    finConn.rollback();
                    sendResponse(exchange, 404, "{\"status\":\"error\",\"message\":\"Finance record not found\"}");
                    return;
                }
                commPct = rs.getDouble("Commission_Percentage") > 0 ? rs.getDouble("Commission_Percentage") : FALLBACK_COMMISSION_PERCENT;
                bankAcc = rs.getString("Account_Number");
                bankName = rs.getString("Bank_Name");
            }

            netRev = round2(totalRevenue - (totalRevenue * commPct / 100.0));
            double balanceAmount = round2(netRev - requestedAmount);

            if (requestedAmount > netRev) {
                finConn.rollback();
                sendResponse(exchange, 200, "{\"status\":\"error\",\"message\":\"Insufficient funds. Net revenue: " + netRev + "\"}");
                return;
            }

            // 2. Update Partner_Finance
            String updateSQL = "UPDATE Partner_Finance SET Total_Revenue=?, Net_Revenue=?, Pending_Payout=?, Paid_Payout=?, Last_Payout_Date=? WHERE Partner_ID=?";
            try (PreparedStatement ps = finConn.prepareStatement(updateSQL)) {
                ps.setDouble(1, totalRevenue);
                ps.setDouble(2, netRev);
                ps.setDouble(3, balanceAmount);
                ps.setDouble(4, requestedAmount);
                ps.setDate(5, new java.sql.Date(System.currentTimeMillis()));
                ps.setString(6, partnerId);
                ps.executeUpdate();
            }

            // 3. Insert Transaction with ENUM CASTS
            String txId = "TX_" + System.currentTimeMillis();
            String insertSQL = "INSERT INTO Partner_Transactions (Partner_ID, Transaction_ID, Transaction_Date, Total_Amount, " +
                             "Withdrawal_Amount, Balance_Amount, Status, Transaction_Type, Comments) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?::payment_status_enum, ?::transaction_type_enum, ?)";

            try (PreparedStatement ps = finConn.prepareStatement(insertSQL)) {
                ps.setString(1, partnerId);
                ps.setString(2, txId);
                ps.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
                ps.setDouble(4, netRev);
                ps.setDouble(5, requestedAmount);
                ps.setDouble(6, balanceAmount);
                ps.setString(7, "Requested");
                ps.setString(8, "PAYOUT");
                ps.setString(9, comments);
                ps.executeUpdate();
            }

            // 4. Finalize DB work before starting email thread
            finConn.commit();

            // 5. Trigger Email (Passing raw data, not the connection)
            triggerPayoutEmail(partnerId, txId, requestedAmount, balanceAmount, bankAcc, bankName, comments);

            sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"Payout requested\",\"transaction_id\":\"" + txId + "\"}");

        } catch (Exception e) {
            if (finConn != null) try { finConn.rollback(); } catch (SQLException ignored) {}
            e.printStackTrace();
            sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
        } finally {
            if (finConn != null) try { finConn.close(); } catch (SQLException ignored) {}
            if (bookConn != null) try { bookConn.close(); } catch (SQLException ignored) {}
        }
    }

    private void triggerPayoutEmail(String pid, String txId, double amt, double bal, String acc, String bank, String comm) {
        new Thread(() -> {
            // Background thread opens its OWN fresh connection
            try (Connection conn = dbConfig.getPartnerDataSource().getConnection()) {
                String sql = "SELECT partner_name, email FROM partner_data WHERE partner_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, pid);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        String name = rs.getString("partner_name");
                        String email = rs.getString("email");
                        String maskedAcc = (acc != null && acc.length() > 4) ? "XXXX" + acc.substring(acc.length() - 4) : acc;

                        System.out.println("[EmailService] Dispatching payout notification to: " + email);

                        EmailService emailService = new EmailService(dbConfig.getEmailApiKey(), dbConfig.getSenderEmail());
                        String subject = "Payout Request Received - " + txId;
                        String body = "Hello " + name + ",\n\n" +
                                     "Your payout request has been successfully submitted.\n\n" +
                                     "Requested Amount: ₹" + amt + "\n" +
                                     "Pending Balance: ₹" + bal + "\n" +
                                     "Bank Name: " + bank + "\n" +
                                     "Account Number: " + maskedAcc + "\n" +
                                     "Status: REQUESTED\n" +
                                     "Comments: " + comm + "\n\n" +
                                     "The amount will be processed within our standard settlement period.\n\n" +
                                     "Regards,\nFinance Team";
                        
                        emailService.sendEmail(email, subject, body);
                        System.out.println("[EmailService] Notification sent successfully for TX: " + txId);
                    }
                }
            } catch (Exception e) { 
                System.err.println("[EmailService] Critical Failure: " + e.getMessage());
                e.printStackTrace(); 
            }
        }).start();
    }

    private double computeTotalRevenueFromBookings(Connection conn, String pid) throws SQLException {
        String sql = "SELECT SUM(original_amount) FROM bookings_info WHERE partner_id=? AND booking_status='COMPLETED'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pid);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getDouble(1) : 0.0;
        }
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private String readBody(HttpExchange exchange) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line; while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private Map<String, String> parseForm(String body) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        for (String p : body.split("&")) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2) map.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
        }
        return map;
    }

    private void sendResponse(HttpExchange exchange, int code, String msg) throws IOException {
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }
}