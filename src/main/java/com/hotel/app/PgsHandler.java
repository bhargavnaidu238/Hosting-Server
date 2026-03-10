package com.hotel.app;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;

public class PgsHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public PgsHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Add CORS headers
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        URI uri = exchange.getRequestURI();
        String path = uri.getPath();

        try {
            // Serve images
            if (path.startsWith("/hotel_images/")) {
                String fileName = path.substring("/hotel_images/".length());
                serveImage(exchange, fileName);
                return;
            }

            // GET paying_guest
            if (path.startsWith("/paying_guest")) {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Method Not Allowed");
                    return;
                }

                List<Map<String, Object>> pgsList = new ArrayList<>();
                String sql = "SELECT * FROM paying_guest_info WHERE Status = 'Active'";

                try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql);
                     ResultSet rs = stmt.executeQuery()) {

                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();

                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            String key = meta.getColumnLabel(i);
                            Object val = rs.getObject(i);
                            
                            // Process PG_Images into a CSV string of full URLs
                            if ("pg_images".equalsIgnoreCase(key)) {
                                row.put("pg_images", buildImageCsv(rs.getString("pg_images")));
                            } else {
                                row.put(key, val == null ? "" : val.toString());
                            }
                        }
                        pgsList.add(row);
                    }
                    sendJson(exchange, pgsList);

                } catch (SQLException e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Database error: " + e.getMessage());
                }
                return;
            }

            sendError(exchange, 404, "Unknown endpoint: " + path);
        } catch (Exception ex) {
            ex.printStackTrace();
            sendError(exchange, 500, "Internal server error: " + ex.getMessage());
        }
    }

    /**
     * FIXED: Processes raw DB string and returns a single comma-separated string.
     * Handles Supabase URLs and local paths properly.
     */
    private String buildImageCsv(String raw) {
        if (raw == null || raw.isBlank()) return "";
        
        // Remove brackets or quotes if they exist from accidental JSON storage
        String cleanRaw = raw.trim();
        if (cleanRaw.startsWith("[") && cleanRaw.endsWith("]")) {
            cleanRaw = cleanRaw.substring(1, cleanRaw.length() - 1);
        }

        String[] parts = cleanRaw.split(",");
        List<String> fixedUrls = new ArrayList<>();

        for (String p : parts) {
            String t = p.trim().replace("\"", ""); // Remove extra quotes
            if (t.isEmpty()) continue;

            if (t.toLowerCase().startsWith("http")) {
                // Already a full URL (Supabase/Web)
                fixedUrls.add(t.replace("localhost", "10.0.2.2"));
            } else {
                // Local filename - prepend server path
                fixedUrls.add("http://10.0.2.2:8080/hotel_images/" + t);
            }
        }
        return String.join(",", fixedUrls);
    }

    private void serveImage(HttpExchange exchange, String fileName) throws IOException {
        fileName = fileName.replaceAll("[/\\\\]+", "");
        File file = new File(dbConfig.getHotelImagesPath(), fileName);

        if (!file.exists() || file.isDirectory()) {
            sendError(exchange, 404, "Image not found");
            return;
        }

        String contentType = Files.probeContentType(file.toPath());
        if (contentType == null) contentType = "application/octet-stream";

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, file.length());

        try (OutputStream os = exchange.getResponseBody(); FileInputStream fis = new FileInputStream(file)) {
            fis.transferTo(os);
        }
    }

    private void sendJson(HttpExchange exchange, List<Map<String, Object>> data) throws IOException {
        String json = toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        String err = "{\"error\":\"" + escape(msg) + "\"}";
        byte[] bytes = err.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String toJson(List<Map<String, Object>> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> m = list.get(i);
            sb.append("{");
            int j = 0;
            for (var e : m.entrySet()) {
                sb.append("\"").append(escape(e.getKey())).append("\":\"")
                  .append(escape(String.valueOf(e.getValue()))).append("\"");
                if (j++ < m.size() - 1) sb.append(",");
            }
            sb.append("}");
            if (i < list.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}