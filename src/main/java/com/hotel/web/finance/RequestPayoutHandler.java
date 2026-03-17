package com.hotel.web.finance;

import com.hotel.utilities.DbConfig;
import com.hotel.notification.service.EmailService; // Ensure this import exists
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.sql.Date;
import java.util.*;

public class RequestPayoutHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public RequestPayoutHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    private static final double MIN_WITHDRAWAL = 5000.0;
    private static final double FALLBACK_COMMISSION_PERCENT = 15.0;

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

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
        double requestedAmount;

        try {
            requestedAmount = Double.parseDouble(params.getOrDefault("amount", "0"));
        } catch (Exception e) {
            requestedAmount = 0;
        }

        String comments = params.getOrDefault("comments", "").trim();
        if (comments.isEmpty())
            comments = "User Requested Payment";

        if (partnerId == null || partnerId.isEmpty()) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"partner_id is required\"}");
            return;
        }

        if (requestedAmount < MIN_WITHDRAWAL) {
            sendResponse(exchange, 200,
                    "{\"status\":\"error\",\"message\":\"Minimum withdrawal ₹" + MIN_WITHDRAWAL + "\"}");
            return;
        }

        Connection finConn = null;
        Connection bookConn = null;
        boolean oldAutoCommit = true;

        try {
            /** COMPUTE COMPLETED BOOKINGS REVENUE **/
            bookConn = dbConfig.getCustomerDataSource().getConnection();
            double totalRevenue = computeTotalRevenueFromBookings(bookConn, partnerId);
            totalRevenue = round2(totalRevenue);

            /** FETCH FINANCE ROW WITH LOCK **/
            finConn = dbConfig.getPartnerDataSource().getConnection();
            oldAutoCommit = finConn.getAutoCommit();
            finConn.setAutoCommit(false);

            double commissionPercent = 0;
            String selectSQL = "SELECT commission_percentage, paid_payout, pending_payout FROM partner_finance WHERE partner_id=? FOR UPDATE";

            try (PreparedStatement ps = finConn.prepareStatement(selectSQL)) {
                ps.setString(1, partnerId);
                ResultSet rs = ps.executeQuery();

                if (!rs.next()) {
                    finConn.rollback();
                    sendResponse(exchange, 404, "{\"status\":\"error\",\"message\":\"Partner not found\"}");
                    return;
                }

                commissionPercent = rs.getDouble("commission_percentage");
                if (commissionPercent <= 0)
                    commissionPercent = FALLBACK_COMMISSION_PERCENT;
            }

            double commissionAmount = round2(totalRevenue * commissionPercent / 100.0);
            double netRevenue = round2(totalRevenue - commissionAmount);
            requestedAmount = round2(requestedAmount);

            double balanceAmount = round2(netRevenue - requestedAmount);
            if (balanceAmount < 0) balanceAmount = 0.0;

            if (requestedAmount > netRevenue) {
                finConn.rollback();
                sendResponse(exchange, 200,
                        "{\"status\":\"error\",\"message\":\"Requested amount exceeds available payout (" + netRevenue + ")\"}");
                return;
            }

            /** UPDATE FINANCE TABLE **/
            String updateFinanceSQL = """
                    UPDATE partner_finance
                    SET total_revenue = ?,
                        commission_percentage = ?,
                        net_revenue = ?,
                        pending_payout = ?,
                        paid_payout = ?,
                        last_payout_date = ?
                    WHERE partner_id = ?
                    """;

            Date txDate = new java.sql.Date(System.currentTimeMillis());

            try (PreparedStatement upd = finConn.prepareStatement(updateFinanceSQL)) {
                upd.setDouble(1, totalRevenue);
                upd.setDouble(2, commissionPercent);
                upd.setDouble(3, netRevenue);
                upd.setDouble(4, balanceAmount);
                upd.setDouble(5, requestedAmount);
                upd.setDate(6, txDate);
                upd.setString(7, partnerId);
                upd.executeUpdate();
            }

            /** INSERT TRANSACTION **/
            String txId = "TX_" + System.currentTimeMillis();
            String finalComments = comments;

            String insert = """
                    INSERT INTO partner_transactions
                    (partner_id, transaction_id, transaction_date, total_amount, withdrawal_amount, balance_amount,
                     status, transaction_type, comments)
                    VALUES (?, ?, ?, ?, ?, ?, ?::payment_status_enum, ?::transaction_type_enum, ?)
                    """;

            try (PreparedStatement ins = finConn.prepareStatement(insert)) {
                ins.setString(1, partnerId);
                ins.setString(2, txId);
                ins.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
                ins.setDouble(4, netRevenue);
                ins.setDouble(5, requestedAmount);
                ins.setDouble(6, balanceAmount);
                ins.setString(7, "Requested");
                ins.setString(8, "PAYOUT");
                ins.setString(9, finalComments);
                ins.executeUpdate();
            }

            finConn.commit();

            /** FETCH PARTNER EMAIL & BANK INFO FOR NOTIFICATION **/
            String partnerDetailsSQL = "SELECT partner_name, email, bank_account_number FROM partner_data WHERE partner_id = ?";
            String partnerName = "Partner";
            String partnerEmail = null;
            String bankAccount = "N/A";

            try (PreparedStatement psDetail = finConn.prepareStatement(partnerDetailsSQL)) {
                psDetail.setString(1, partnerId);
                ResultSet rsDetail = psDetail.executeQuery();
                if (rsDetail.next()) {
                    partnerName = rsDetail.getString("partner_name");
                    partnerEmail = rsDetail.getString("email");
                    String fullAcc = rsDetail.getString("bank_account_number");
                    bankAccount = (fullAcc != null && fullAcc.length() > 4) 
                                  ? "XXXX" + fullAcc.substring(fullAcc.length() - 4) 
                                  : fullAcc;
                }
            }

            /** SEND EMAIL NOTIFICATION (ASYNC) **/
            if (partnerEmail != null) {
                final String emailTo = partnerEmail;
                final String name = partnerName;
                final String acc = bankAccount;
                final double reqAmt = requestedAmount;
                final double pendAmt = balanceAmount;
                
                new Thread(() -> {
                    try {
                        EmailService emailService = new EmailService(dbConfig.getEmailApiKey(), dbConfig.getSenderEmail());
                        String subject = "Payout Request Received - " + txId;
                        String msg = "Hello " + name + ",\n\n" +
                                     "We have received your payout request. Details are below:\n\n" +
                                     "Transaction ID: " + txId + "\n" +
                                     "Requested Amount: ₹" + reqAmt + "\n" +
                                     "Remaining Balance: ₹" + pendAmt + "\n" +
                                     "Settlement Account: " + acc + "\n" +
                                     "Status: REQUESTED\n" +
                                     "Comments: " + finalComments + "\n\n" +
                                     "Your request is being processed by our finance team.\n\n" +
                                     "Regards,\nHotel Management Team";
                        
                        emailService.sendEmail(emailTo, subject, msg);
                    } catch (Exception e) {
                        System.err.println("Payout Email Failed: " + e.getMessage());
                    }
                }).start();
            }

            sendResponse(exchange, 200,
                    "{\"status\":\"success\",\"message\":\"Payout requested\",\"transaction_id\":\"" + txId + "\"}");

        } catch (Exception e) {
            e.printStackTrace();
            try { if (finConn != null) finConn.rollback(); } catch (Exception ignored) {}
            sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
        } finally {
            try { if (finConn != null) { finConn.setAutoCommit(oldAutoCommit); finConn.close(); }} catch (Exception ignored) {}
            try { if (bookConn != null) bookConn.close(); } catch (Exception ignored) {}
        }
    }

    private double computeTotalRevenueFromBookings(Connection conn, String partnerId) throws Exception {
        String sql = "SELECT SUM(original_amount) AS total FROM bookings_info WHERE partner_id=? AND booking_status='COMPLETED'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, partnerId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getDouble("total") : 0.0;
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
        if (body == null) return map;
        for (String p : body.split("&")) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2) map.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
        }
        return map;
    }

    private void sendResponse(HttpExchange exchange, int code, String msg) throws IOException {
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }
}