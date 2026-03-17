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

    // Validation sets in lowercase for easy comparison
    private static final Set<String> VALID_PAYOUT_TYPES =
            Set.of("daily", "weekly", "fortnight", "monthly", "quarterly");

    private static final Set<String> VALID_ACCOUNT_TYPES =
            Set.of("current", "savings");

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        // 1. CORS Headers
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

        // 2. Extract values using BOTH possible case keys (robustness)
        String accountHolderName = getParam(params, "Account_Holder_Name", "account_holder_name");
        String bankName = getParam(params, "Bank_Name", "bank_name");
        String accountNumber = getParam(params, "Account_Number", "account_number").trim();
        String ifscSwift = getParam(params, "IFSC_SWIFT", "ifsc_swift");
        String panTaxId = getParam(params, "PAN_Tax_ID", "pan_tax_id").trim().toUpperCase();

        // 3. Extract and Normalize ENUM types
        String rawAccountType = getParam(params, "Account_Type", "account_type").trim().toLowerCase();
        String rawPayoutType = getParam(params, "Payout_Type", "payout_type").trim().toLowerCase();

        // 4. Validation
        if (!VALID_ACCOUNT_TYPES.contains(rawAccountType)) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid account type: " + rawAccountType + "\"}");
            return;
        }

        if (!VALID_PAYOUT_TYPES.contains(rawPayoutType)) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid payout type: " + rawPayoutType + "\"}");
            return;
        }

        // 5. Database Interaction
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection()) {

            // Check uniqueness (Account Number)
            if (exists(conn, "SELECT 1 FROM partner_finance WHERE account_number = ? AND partner_id <> ?", accountNumber, partnerId)) {
                sendResponse(exchange, 409, "{\"status\":\"error\",\"message\":\"Account Number already registered\"}");
                return;
            }

            // Check uniqueness (PAN)
            if (exists(conn, "SELECT 1 FROM partner_finance WHERE pan_tax_id = ? AND partner_id <> ?", panTaxId, partnerId)) {
                sendResponse(exchange, 409, "{\"status\":\"error\",\"message\":\"PAN / Tax ID already registered\"}");
                return;
            }

            // Normalize for Postgres ENUM (Capitalized first letter)
            String dbAccountType = capitalize(rawAccountType);
            String dbPayoutType = capitalize(rawPayoutType);

            boolean alreadyExists = exists(conn, "SELECT 1 FROM partner_finance WHERE partner_id = ?", partnerId);

            if (alreadyExists) {
                String sql = "UPDATE partner_finance SET account_holder_name=?, bank_name=?, account_number=?, ifsc_swift=?, account_type=?, pan_tax_id=?, payout_type=? WHERE partner_id=?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, accountHolderName);
                    ps.setString(2, bankName);
                    ps.setString(3, accountNumber);
                    ps.setString(4, ifscSwift);
                    ps.setObject(5, dbAccountType, Types.OTHER);
                    ps.setString(6, panTaxId);
                    ps.setObject(7, dbPayoutType, Types.OTHER);
                    ps.setString(8, partnerId);
                    ps.executeUpdate();
                }
            } else {
                String sql = "INSERT INTO partner_finance (partner_id, account_holder_name, bank_name, account_number, ifsc_swift, account_type, pan_tax_id, payout_type) VALUES (?,?,?,?,?,?,?,?)";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, partnerId);
                    ps.setString(2, accountHolderName);
                    ps.setString(3, bankName);
                    ps.setString(4, accountNumber);
                    ps.setString(5, ifscSwift);
                    ps.setObject(6, dbAccountType, Types.OTHER);
                    ps.setString(7, panTaxId);
                    ps.setObject(8, dbPayoutType, Types.OTHER);
                    ps.executeUpdate();
                }
            }

            sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"Bank details saved successfully\"}");

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    // Helper to get params checking both CamelCase and snake_case
    private String getParam(Map<String, String> params, String key1, String key2) {
        if (params.containsKey(key1)) return params.get(key1);
        if (params.containsKey(key2)) return params.get(key2);
        return "";
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private boolean exists(Connection conn, String sql, String... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
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

    private String escape(String s) { return s.replace("\"", "\\\""); }

    private void sendResponse(HttpExchange exchange, int code, String msg) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }
}