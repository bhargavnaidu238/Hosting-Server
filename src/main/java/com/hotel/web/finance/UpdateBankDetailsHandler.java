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

    // Normalized sets for internal validation
    private static final Set<String> VALID_PAYOUT_TYPES =
            Set.of("daily", "weekly", "fortnight", "monthly", "quarterly");

    private static final Set<String> VALID_ACCOUNT_TYPES =
            Set.of("current", "savings");

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        // CORS Setup
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

        // Fetch ID with dual-case support
        String partnerId = getParam(params, "partner_id", "Partner_ID");
        if (partnerId.isEmpty()) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"partner_id is required\"}");
            return;
        }

        // Extract and Normalize ENUM strings
        String rawAccountType = getParam(params, "Account_Type", "account_type").trim().toLowerCase();
        String rawPayoutType = getParam(params, "Payout_Type", "payout_type").trim().toLowerCase();

        if (!VALID_ACCOUNT_TYPES.contains(rawAccountType)) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid account type: " + rawAccountType + "\"}");
            return;
        }

        if (!VALID_PAYOUT_TYPES.contains(rawPayoutType)) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid payout type: " + rawPayoutType + "\"}");
            return;
        }

        // Prepare Database-ready strings (matching your ENUM: 'Savings', 'Current')
        String dbAccountType = capitalize(rawAccountType);
        String dbPayoutType = capitalize(rawPayoutType);

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection()) {

            String accountHolder = getParam(params, "Account_Holder_Name", "account_holder_name");
            String bankName = getParam(params, "Bank_Name", "bank_name");
            String accountNum = getParam(params, "Account_Number", "account_number").trim();
            String ifsc = getParam(params, "IFSC_SWIFT", "ifsc_swift");
            String pan = getParam(params, "PAN_Tax_ID", "pan_tax_id").trim().toUpperCase();

            // Check if record exists using exact Schema Casing
            boolean alreadyExists = exists(conn, "SELECT 1 FROM Partner_Finance WHERE Partner_ID = ?", partnerId);

            if (alreadyExists) {
                // UPDATE statement with exact Schema Column names
                String sql = """
                        UPDATE Partner_Finance SET
                        Account_Holder_Name=?, Bank_Name=?, Account_Number=?, IFSC_SWIFT=?,
                        Account_Type=?, PAN_Tax_ID=?, Payout_Type=?
                        WHERE Partner_ID=?
                        """;

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, accountHolder);
                    ps.setString(2, bankName);
                    ps.setString(3, accountNum);
                    ps.setString(4, ifsc);
                    ps.setObject(5, dbAccountType, Types.OTHER);
                    ps.setString(6, pan);
                    ps.setObject(7, dbPayoutType, Types.OTHER);
                    ps.setString(8, partnerId);
                    ps.executeUpdate();
                }
            } else {
                // INSERT statement with exact Schema Column names
                String sql = """
                        INSERT INTO Partner_Finance
                        (Partner_ID, Account_Holder_Name, Bank_Name, Account_Number, IFSC_SWIFT, 
                         Account_Type, PAN_Tax_ID, Payout_Type)
                        VALUES (?,?,?,?,?,?,?,?)
                        """;

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, partnerId);
                    ps.setString(2, accountHolder);
                    ps.setString(3, bankName);
                    ps.setString(4, accountNum);
                    ps.setString(5, ifsc);
                    ps.setObject(6, dbAccountType, Types.OTHER);
                    ps.setString(7, pan);
                    ps.setObject(8, dbPayoutType, Types.OTHER);
                    ps.executeUpdate();
                }
            }

            sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"Bank details updated successfully\"}");

        } catch (SQLException e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"Database Error: " + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"Internal Server Error\"}");
        }
    }

    // Helper to find parameters regardless of key casing
    private String getParam(Map<String, String> params, String key1, String key2) {
        if (params.containsKey(key1)) return params.get(key1);
        if (params.containsKey(key2)) return params.get(key2);
        return "";
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private boolean exists(Connection conn, String sql, String val) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, val);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
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
                map.put(java.net.URLDecoder.decode(kv[0], "UTF-8"), java.net.URLDecoder.decode(kv[1], "UTF-8"));
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