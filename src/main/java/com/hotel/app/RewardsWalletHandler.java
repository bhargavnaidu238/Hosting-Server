package com.hotel.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class RewardsWalletHandler implements HttpHandler {

    private final DbConfig dbConfig;
    private final ObjectMapper mapper = new ObjectMapper();

    public RewardsWalletHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    private Connection getConnection() throws SQLException {
        return dbConfig.getCustomerDataSource().getConnection();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        ObjectNode response = mapper.createObjectNode();

        try {
            // Routing Logic based on your server.createContext setup
            if (path.equals("/user-rewards-full") && "GET".equalsIgnoreCase(method)) {
                response = handleFullRewardsRequest(exchange);
            } else if (path.equals("/coupon/validate") && "POST".equalsIgnoreCase(method)) {
                response = handleCouponValidate(exchange);
            } else if (path.equals("/wallet") && "GET".equalsIgnoreCase(method)) {
                // Maintain legacy support for basic wallet info
                response = handleLegacyWalletRequest(exchange);
            } else {
                response.put("status", "success");
                response.put("message", "Endpoint " + path + " active. Processing logic pending...");
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.put("error", "Internal Server Error: " + e.getMessage());
        }

        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private ObjectNode handleFullRewardsRequest(HttpExchange exchange) throws Exception {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
        String userId = params.get("userId");
        ObjectNode root = mapper.createObjectNode();

        if (userId == null) return root.put("error", "User ID required");

        try (Connection conn = getConnection()) {
            // 1. Wallet Balance
            ObjectNode wallet = mapper.createObjectNode();
            String wSql = "SELECT wallet_id, balance FROM wallet WHERE user_id = ? LIMIT 1";
            String walletId = "";
            try (PreparedStatement ps = conn.prepareStatement(wSql)) {
                ps.setString(1, userId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    walletId = rs.getString("wallet_id");
                    wallet.put("balance", rs.getDouble("balance"));
                } else {
                    wallet.put("balance", 0.0);
                }
            }
            root.set("wallet", wallet);

            // 2. Referral Progress (Gamification)
            ObjectNode refStats = mapper.createObjectNode();
            String refSql = "SELECT COUNT(*) FROM referrals WHERE referrer_id = ?::uuid AND is_qualified = true";
            int count = 0;
            try (PreparedStatement ps = conn.prepareStatement(refSql)) {
                ps.setString(1, userId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) count = rs.getInt(1);
            }
            
            // Find next milestone (e.g. 5, 10, etc)
            String mileSql = "SELECT referrals_required FROM referral_milestones WHERE referrals_required > ? ORDER BY referrals_required LIMIT 1";
            int nextMile = 5; 
            try (PreparedStatement ps = conn.prepareStatement(mileSql)) {
                ps.setInt(1, count);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) nextMile = rs.getInt(1);
            }
            refStats.put("completed_count", count);
            refStats.put("next_milestone", nextMile);
            root.set("referral_stats", refStats);

            // 3. Available Coupons
            ArrayNode coupons = mapper.createArrayNode();
            String cSql = "SELECT coupon_code, title, description, valid_to FROM coupons WHERE status = 'active' AND valid_to > NOW()";
            try (PreparedStatement ps = conn.prepareStatement(cSql)) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    ObjectNode c = mapper.createObjectNode();
                    c.put("coupon_code", rs.getString("coupon_code"));
                    c.put("title", rs.getString("title"));
                    c.put("description", rs.getString("description"));
                    c.put("valid_to", rs.getTimestamp("valid_to").toString());
                    coupons.add(c);
                }
            }
            root.set("coupons", coupons);

            // 4. Transactions (For Tabs)
            ArrayNode txns = mapper.createArrayNode();
            if (!walletId.isEmpty()) {
                String tSql = "SELECT description, amount, direction, created_at FROM wallet_transactions WHERE wallet_id = ? ORDER BY created_at DESC";
                try (PreparedStatement ps = conn.prepareStatement(tSql)) {
                    ps.setString(1, walletId);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        ObjectNode t = mapper.createObjectNode();
                        t.put("description", rs.getString("description"));
                        t.put("amount", rs.getDouble("amount"));
                        t.put("direction", rs.getString("direction"));
                        t.put("created_at", rs.getTimestamp("created_at").toString());
                        txns.add(t);
                    }
                }
            }
            root.set("transactions", txns);

            // 5. Refunds (For Tabs)
            ArrayNode refunds = mapper.createArrayNode();
            String rSql = "SELECT refund_id, refunded_amount, status FROM refunds WHERE txn_id IN (SELECT txn_id FROM wallet_transactions WHERE wallet_id = ?)";
            try (PreparedStatement ps = conn.prepareStatement(rSql)) {
                ps.setString(1, walletId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    ObjectNode r = mapper.createObjectNode();
                    r.put("refund_id", rs.getString("refund_id"));
                    r.put("refunded_amount", rs.getDouble("refunded_amount"));
                    r.put("status", rs.getString("status"));
                    refunds.add(r);
                }
            }
            root.set("refunds", refunds);
        }
        return root;
    }

    private ObjectNode handleCouponValidate(HttpExchange exchange) throws Exception {
        // Implement your existing coupon validation logic here
        return mapper.createObjectNode().put("valid", true).put("message", "Validating...");
    }

    private ObjectNode handleLegacyWalletRequest(HttpExchange exchange) {
        return mapper.createObjectNode().put("balance", 0.0);
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=");
            if (kv.length > 1) result.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return result;
    }
}