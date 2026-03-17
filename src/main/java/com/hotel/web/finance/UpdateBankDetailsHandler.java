package com.hotel.web.finance;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class UpdateBankDetailsHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public UpdateBankDetailsHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    // Fixed typo: Fortnight
    private static final Set<String> VALID_PAYOUT_TYPES =
            Set.of("Daily", "Weekly", "Fornight", "Monthly", "Quarterly");

    private static final Set<String> VALID_ACCOUNT_TYPES =
            Set.of("Current", "Savings");

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
            sendResponse(exchange, 405, "{\"status\":\"error\",\"message\":\"Only POST allowed\"}");
            return;
        }

        String body = readBody(exchange);
        Map<String, String> params = parseForm(body);

        String partnerId = params.get("partner_id");
        if (partnerId == null || partnerId.isEmpty()) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"partner_id is required\"}");
            return;
        }

        String accountHolderName = params.getOrDefault("Account_Holder_Name", "");
        String bankName = params.getOrDefault("Bank_Name", "");
        String accountNumber = params.getOrDefault("Account_Number", "");
        String ifscSwift = params.getOrDefault("IFSC_SWIFT", "");
        
        // Fix: Normalize input strings (trim and capitalize first letter to match Set/Enum)
        String accountType = capitalize(params.getOrDefault("Account_Type", "").trim().toLowerCase());
        String payoutType = capitalize(params.getOrDefault("Payout_Type", "").trim().toLowerCase());
        String panTaxId = params.getOrDefault("PAN_Tax_ID", "").trim().toUpperCase();

        if (!VALID_ACCOUNT_TYPES.contains(accountType)) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid account type: " + accountType + "\"}");
            return;
        }

        if (!VALID_PAYOUT_TYPES.contains(payoutType)) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid payout type: " + payoutType + "\"}");
            return;
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection()) {

            // Check unique Account Number
            if (exists(conn,
                    "SELECT partner_id FROM partner_finance WHERE account_number = ? AND partner_id <> ?",
                    accountNumber, partnerId)) {
                sendResponse(exchange, 409,
                        "{\"status\":\"error\",\"message\":\"Account Number already registered by another partner\"}");
                return;
            }

            // Check unique PAN number
            if (exists(conn,
                    "SELECT partner_id FROM partner_finance WHERE pan_tax_id = ? AND partner_id <> ?",
                    panTaxId, partnerId)) {
                sendResponse(exchange, 409,
                        "{\"status\":\"error\",\"message\":\"PAN / Tax ID already registered by another partner\"}");
                return;
            }

            boolean alreadyExists = exists(conn,
                    "SELECT partner_id FROM partner_finance WHERE partner_id = ?", partnerId);

            if (alreadyExists) {
                String sql = """
                        UPDATE partner_finance SET
                        account_holder_name=?, bank_name=?, account_number=?, ifsc_swift=?,
                        account_type=?, pan_tax_id=?, payout_type=?
                        WHERE partner_id=?
                        """;

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, accountHolderName);
                    ps.setString(2, bankName);
                    ps.setString(3, accountNumber);
                    ps.setString(4, ifscSwift);
                    ps.setObject(5, accountType, Types.OTHER);
                    ps.setString(6, panTaxId);
                    ps.setObject(7, payoutType, Types.OTHER);
                    ps.setString(8, partnerId);
                    ps.executeUpdate();
                }
            } else {
                String sql = """
                        INSERT INTO partner_finance
                        (partner_id, account_holder_name, bank_name, account_number, ifsc_swift,
                         account_type, pan_tax_id, payout_type)
                        VALUES (?,?,?,?,?,?,?,?)
                        """;

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, partnerId);
                    ps.setString(2, accountHolderName);
                    ps.setString(3, bankName);
                    ps.setString(4, accountNumber);
                    ps.setString(5, ifscSwift);
                    ps.setObject(6, accountType, Types.OTHER);
                    ps.setString(7, panTaxId);
                    ps.setObject(8, payoutType, Types.OTHER);
                    ps.executeUpdate();
                }
            }

            sendResponse(exchange, 200,
                    "{\"status\":\"success\",\"message\":\"Bank / finance details saved successfully\"}");

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500,
                    "{\"status\":\"error\",\"message\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    // Helper to ensure "savings" becomes "Savings" to match the ENUM
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private boolean exists(Connection conn, String sql, String value) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean exists(Connection conn, String sql, String v1, String v2) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, v1);
            ps.setString(2, v2);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private Map<String, String> parseForm(String body) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2)
                map.put(java.net.URLDecoder.decode(kv[0], "UTF-8"),
                        java.net.URLDecoder.decode(kv[1], "UTF-8"));
        }
        return map;
    }

    private String escape(String s) {
        return s.replace("\"", "\\\"");
    }

    private void sendResponse(HttpExchange exchange, int code, String msg) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}