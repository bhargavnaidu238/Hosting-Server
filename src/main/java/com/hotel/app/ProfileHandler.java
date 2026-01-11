package com.hotel.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.security.PasswordUtil;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.sql.*;
import java.util.*;

public class ProfileHandler implements HttpHandler {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final DbConfig dbConfig;

    public ProfileHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            /* ================= CHANGE PASSWORD ================= */
            if ("POST".equalsIgnoreCase(method) && "/app/change-password".equals(path)) {
                Map<String, Object> request =
                        mapper.readValue(exchange.getRequestBody(), Map.class);
                handleChangePassword(exchange, request);
                return;
            }

            /* ================= EXISTING LOGIC ================= */
            if ("GET".equalsIgnoreCase(method)) {
                handleGetProfile(exchange);

            } else if ("POST".equalsIgnoreCase(method)) {
                Map<String, Object> request =
                        mapper.readValue(exchange.getRequestBody(), Map.class);

                if (request.containsKey("status")
                        && "Inactive".equalsIgnoreCase((String) request.get("status"))) {
                    handleDeactivateAccount(exchange, request);
                } else {
                    handleUpdateProfile(exchange, request);
                }

            } else {
                sendResponse(exchange, 405, Map.of("error", "Method not allowed"));
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, Map.of("error", "Internal Server Error"));
        }
    }

    /* ================= CHANGE PASSWORD ================= */

    private void handleChangePassword(HttpExchange exchange,
                                      Map<String, Object> request) throws IOException {

        String email = getSafeString(request.get("email")).toLowerCase();
        String currentPassword = getSafeString(request.get("currentPassword"));
        String newPassword = getSafeString(request.get("newPassword"));

        boolean success = false;

        try (Connection conn = getConnection()) {

            String selectSql =
                    "SELECT Password FROM user_info WHERE LOWER(user_email)=? AND status='Active'";

            PreparedStatement ps = conn.prepareStatement(selectSql);
            ps.setString(1, email);

            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                String storedHash = rs.getString("password");

                if (PasswordUtil.verifyPassword(currentPassword, storedHash)) {

                    String newHash = PasswordUtil.hashPassword(newPassword);

                    String updateSql =
                            "UPDATE user_info SET password=? WHERE LOWER(user_email)=?";

                    PreparedStatement ups = conn.prepareStatement(updateSql);
                    ups.setString(1, newHash);
                    ups.setString(2, email);

                    success = ups.executeUpdate() > 0;
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
            sendResponse(exchange, 500, Map.of("success", false));
            return;
        }

        sendResponse(exchange, 200, Map.of("success", success));
    }

    /* ================= FETCH PROFILE ================= */

    private void handleGetProfile(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String email = null;

        if (query != null && query.contains("email=")) {
            email = java.net.URLDecoder.decode(query.split("email=")[1], "UTF-8");
        }

        if (email == null || email.isEmpty()) {
            sendResponse(exchange, 400, Map.of("error", "Missing email parameter"));
            return;
        }

        try (Connection conn = getConnection()) {
            String sql = "SELECT * FROM user_info WHERE LOWER(user_email) = ?";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, email.toLowerCase());

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                Map<String, Object> userData = new HashMap<>();
                userData.put("userId", rs.getString("user_id"));
                userData.put("email", rs.getString("user_email"));
                userData.put("firstName", rs.getString("first_name"));
                userData.put("lastName", rs.getString("last_name"));
                userData.put("phone", rs.getString("mobile_number"));
                userData.put("address", rs.getString("address"));
                userData.put("status", rs.getString("status"));
                sendResponse(exchange, 200, userData);
            } else {
                sendResponse(exchange, 404, Map.of("error", "User not found"));
            }

        } catch (SQLException e) {
            e.printStackTrace();
            sendResponse(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    /* ================= UPDATE PROFILE ================= */

    private void handleUpdateProfile(HttpExchange exchange,
                                     Map<String, Object> request) throws IOException {

        String email = getSafeString(request.get("email")).toLowerCase();
        boolean updated;

        try (Connection conn = getConnection()) {
            updated = updateUserInDB(conn, request, email);
        } catch (SQLException e) {
            e.printStackTrace();
            sendResponse(exchange, 500, Map.of("error", e.getMessage()));
            return;
        }

        sendResponse(exchange, updated ? 200 : 404,
                Map.of("message",
                        updated ? "Profile updated successfully"
                                : "User not found or no changes made"));
    }

    private boolean updateUserInDB(Connection conn,
                                   Map<String, Object> data,
                                   String email) throws SQLException {

        String sql =
                "UPDATE user_info SET first_name=?, last_name=?, mobile_number=?, address=? " +
                "WHERE LOWER(user_email)=?";

        PreparedStatement stmt = conn.prepareStatement(sql);
        stmt.setString(1, getSafeString(data.get("firstName")));
        stmt.setString(2, getSafeString(data.get("lastName")));
        stmt.setString(3, getSafeString(data.get("phone")));
        stmt.setString(4, getSafeString(data.get("address")));
        stmt.setString(5, email);

        return stmt.executeUpdate() > 0;
    }

    /* ================= DEACTIVATE ACCOUNT ================= */

    private void handleDeactivateAccount(HttpExchange exchange,
                                         Map<String, Object> request) throws IOException {

        String email = getSafeString(request.get("email")).toLowerCase();
        boolean updated;

        try (Connection conn = getConnection()) {
            String sql =
                    "UPDATE user_info SET status='Inactive' WHERE LOWER(user_email)=?";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, email);
            updated = stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            sendResponse(exchange, 500, Map.of("error", e.getMessage()));
            return;
        }

        sendResponse(exchange, updated ? 200 : 404,
                Map.of("message",
                        updated ? "Account deactivated successfully"
                                : "User not found"));
    }

    /* ================= UTIL ================= */

    private Connection getConnection() throws SQLException {
        Connection conn = dbConfig.getCustomerDataSource().getConnection();
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL JDBC Driver not found.");
        }
        return conn;
    }

    private String getSafeString(Object val) {
        return val == null ? "" : val.toString().trim();
    }

    private void sendResponse(HttpExchange exchange,
                              int status,
                              Map<String, ?> response) throws IOException {

        String json = mapper.writeValueAsString(response);
        exchange.getResponseHeaders()
                .add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, json.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json.getBytes());
        }
    }
}
