package com.hotel.web.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.security.PasswordUtil;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class WebProfileHandler implements HttpHandler {

    // Correct for your schema
    private static final Set<String> READ_ONLY_FIELDS =
            Set.of("email", "user_status", "registration_date", "partner_id");

    private final DbConfig dbConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebProfileHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        setCORS(exchange);

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405,
                    Map.of("status", "error", "message", "Invalid request method"));
            return;
        }

        String body = readRequestBody(exchange);
        Map<String, String> params = parseForm(body);
        String path = exchange.getRequestURI().getPath();

        try {
            switch (path) {
                case "/webgetprofile":
                    handleGetProfile(exchange, params);
                    break;
                case "/webupdateprofile":
                    handleUpdateProfile(exchange, params);
                    break;
                case "/webchangepassword":
                    handleChangePassword(exchange, params);
                    break;
                case "/webdeleteprofile":
                    handleDeleteProfile(exchange, params);
                    break;
                default:
                    sendJson(exchange, 404,
                            Map.of("status", "error", "message", "Invalid endpoint"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, 500,
                    Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // ================= GET PROFILE =================

    private void handleGetProfile(HttpExchange exchange,
                                  Map<String, String> params)
            throws Exception {

        String loggedInEmail =
                params.getOrDefault("loggedInEmail", "").trim().toLowerCase();

        if (loggedInEmail.isEmpty()) {
            sendJson(exchange, 400,
                    Map.of("status", "error",
                           "message", "Logged-in email is required"));
            return;
        }

        String query = "SELECT * FROM partner_data WHERE LOWER(email)=?";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, loggedInEmail);

            try (ResultSet rs = stmt.executeQuery()) {

                if (!rs.next()) {
                    sendJson(exchange, 404,
                            Map.of("status", "error",
                                   "message", "Partner not found"));
                    return;
                }

                Map<String, Object> data = new LinkedHashMap<>();
                ResultSetMetaData meta = rs.getMetaData();

                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String column = meta.getColumnName(i).toLowerCase();
                    Object value = rs.getObject(i);
                    data.put(column, value != null ? value.toString() : "");
                }

                sendJson(exchange, 200,
                        Map.of("status", "success", "data", data));
            }
        }
    }

    // ================= UPDATE PROFILE =================

    private void handleUpdateProfile(HttpExchange exchange,
                                     Map<String, String> params)
            throws Exception {

        String loggedInEmail =
                params.getOrDefault("loggedInEmail", "").trim().toLowerCase();

        if (loggedInEmail.isEmpty()) {
            sendJson(exchange, 400,
                    Map.of("status", "error",
                           "message", "Logged-in email is required"));
            return;
        }

        List<String> columns = new ArrayList<>();

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt =
                     conn.prepareStatement("SELECT * FROM partner_data LIMIT 1");
             ResultSet rs = stmt.executeQuery()) {

            ResultSetMetaData meta = rs.getMetaData();

            for (int i = 1; i <= meta.getColumnCount(); i++) {
                columns.add(meta.getColumnName(i).toLowerCase());
            }
        }

        StringBuilder setClause = new StringBuilder();
        List<String> values = new ArrayList<>();

        for (String col : columns) {
            if (params.containsKey(col)
                    && !READ_ONLY_FIELDS.contains(col)) {

                setClause.append(col).append("=?,");
                values.add(params.get(col).trim());
            }
        }

        if (setClause.length() == 0) {
            sendJson(exchange, 400,
                    Map.of("status", "error",
                           "message", "No editable fields provided"));
            return;
        }

        setClause.setLength(setClause.length() - 1);

        String updateQuery =
                "UPDATE partner_data SET "
                        + setClause
                        + " WHERE LOWER(email)=?";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt =
                     conn.prepareStatement(updateQuery)) {

            int index = 1;

            for (String val : values) {
                stmt.setString(index++, val);
            }

            stmt.setString(index, loggedInEmail);

            int updated = stmt.executeUpdate();

            if (updated == 0) {
                sendJson(exchange, 404,
                        Map.of("status", "error",
                               "message", "Partner not found"));
            } else {
                sendJson(exchange, 200,
                        Map.of("status", "success",
                               "message", "Profile updated successfully"));
            }
        }
    }

    // ================= CHANGE PASSWORD =================

    private void handleChangePassword(HttpExchange exchange,
                                      Map<String, String> params)
            throws Exception {

        String loggedInEmail =
                params.getOrDefault("loggedInEmail", "").trim().toLowerCase();
        String currentPassword =
                params.getOrDefault("currentPassword", "");
        String newPassword =
                params.getOrDefault("newPassword", "");

        if (loggedInEmail.isEmpty()
                || currentPassword.isEmpty()
                || newPassword.isEmpty()) {

            sendJson(exchange, 400,
                    Map.of("status", "error",
                           "message", "All fields required"));
            return;
        }

        try (Connection conn =
                     dbConfig.getPartnerDataSource().getConnection()) {

            String storedHash;

            try (PreparedStatement stmt =
                         conn.prepareStatement(
                                 "SELECT password FROM partner_data WHERE LOWER(email)=?")) {

                stmt.setString(1, loggedInEmail);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        sendJson(exchange, 404,
                                Map.of("status", "error",
                                       "message", "User not found"));
                        return;
                    }
                    storedHash = rs.getString("password");
                }
            }

            if (!PasswordUtil.verifyPassword(currentPassword, storedHash)) {
                sendJson(exchange, 401,
                        Map.of("status", "error",
                               "message", "Current password incorrect"));
                return;
            }

            String newHash = PasswordUtil.hashPassword(newPassword);

            try (PreparedStatement stmt =
                         conn.prepareStatement(
                                 "UPDATE partner_data SET password=? WHERE LOWER(email)=?")) {

                stmt.setString(1, newHash);
                stmt.setString(2, loggedInEmail);
                stmt.executeUpdate();
            }
        }

        sendJson(exchange, 200,
                Map.of("status", "success",
                       "message", "Password updated successfully"));
    }

    // ================= DELETE PROFILE =================

    private void handleDeleteProfile(HttpExchange exchange,
                                     Map<String, String> params)
            throws Exception {

        String loggedInEmail =
                params.getOrDefault("loggedInEmail", "").trim().toLowerCase();

        if (loggedInEmail.isEmpty()) {
            sendJson(exchange, 400,
                    Map.of("status", "error",
                           "message", "Logged-in email required"));
            return;
        }

        String updateQuery =
                "UPDATE partner_data SET user_status='Inactive' WHERE LOWER(email)=?";

        try (Connection conn =
                     dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt =
                     conn.prepareStatement(updateQuery)) {

            stmt.setString(1, loggedInEmail);
            int updated = stmt.executeUpdate();

            if (updated == 0) {
                sendJson(exchange, 404,
                        Map.of("status", "error",
                               "message", "Partner not found"));
                return;
            }
        }

        sendJson(exchange, 200,
                Map.of("status", "success",
                       "message", "Account deactivated successfully",
                       "logout", true));
    }

    // ================= UTILITIES =================

    private void sendJson(HttpExchange exchange,
                          int statusCode,
                          Object response) throws IOException {

        byte[] bytes =
                objectMapper.writeValueAsBytes(response);

        exchange.getResponseHeaders()
                .set("Content-Type", "application/json; charset=UTF-8");

        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void setCORS(HttpExchange exchange) {
        exchange.getResponseHeaders()
                .add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders()
                .add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders()
                .add("Access-Control-Allow-Headers", "Content-Type");
    }

    private String readRequestBody(HttpExchange exchange)
            throws IOException {

        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     exchange.getRequestBody(),
                                     StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            return sb.toString();
        }
    }

    private Map<String, String> parseForm(String body)
            throws UnsupportedEncodingException {

        Map<String, String> map = new HashMap<>();

        if (body == null || body.isEmpty())
            return map;

        for (String pair : body.split("&")) {

            String[] parts = pair.split("=", 2);

            if (parts.length == 2) {

                String key =
                        java.net.URLDecoder.decode(parts[0], "UTF-8");

                String val =
                        java.net.URLDecoder.decode(parts[1], "UTF-8");

                map.put(key, val);
            }
        }

        return map;
    }
}
