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

                String query = uri.getQuery();
                Map<String, String> params = parseQueryParams(query);
                
                String typeFilter = params.get("type");
                String cityFilter = params.get("city");
                String searchFilter = params.get("search");

                // Location parameters for proximity logic
                double userLat = 0.0;
                double userLng = 0.0;
                boolean hasLocation = false;
                try {
                    if (params.containsKey("lat") && params.containsKey("lng")) {
                        userLat = Double.parseDouble(params.get("lat"));
                        userLng = Double.parseDouble(params.get("lng"));
                        hasLocation = true;
                    }
                } catch (Exception e) {
                    hasLocation = false;
                }

                List<Map<String, Object>> pgsList = new ArrayList<>();
                
                // Build SQL with distance calculation
                StringBuilder sql = new StringBuilder("SELECT *");
                if (hasLocation) {
                    // Haversine formula to calculate distance in KM
                    sql.append(", (6371 * acos(cos(radians(?)) * cos(radians(latitude)) * cos(radians(longitude) - radians(?)) + sin(radians(?)) * sin(radians(latitude)))) AS distance");
                }
                
                sql.append(" FROM paying_guest_info WHERE status = 'Active'");
                List<Object> sqlParams = new ArrayList<>();

                if (hasLocation) {
                    sqlParams.add(userLat);
                    sqlParams.add(userLng);
                    sqlParams.add(userLat);
                }

                // 1. Strict Category Type Filter
                if (typeFilter != null && !typeFilter.trim().isEmpty() && !typeFilter.equalsIgnoreCase("All")) {
                    sql.append(" AND LOWER(pg_type) = ?");
                    sqlParams.add(typeFilter.toLowerCase().trim());
                }

                // 2. City Filter
                if (cityFilter != null && !cityFilter.trim().isEmpty()) {
                    sql.append(" AND LOWER(city) = ?");
                    sqlParams.add(cityFilter.toLowerCase().trim());
                }

                // 3. Search Keyword Filter
                if (searchFilter != null && !searchFilter.trim().isEmpty()) {
                    sql.append(" AND (LOWER(pg_name) LIKE ? OR LOWER(city) LIKE ? OR LOWER(address) LIKE ?)");
                    String pattern = "%" + searchFilter.toLowerCase().trim() + "%";
                    sqlParams.add(pattern);
                    sqlParams.add(pattern);
                    sqlParams.add(pattern);
                }

                // 4. Proximity Logic: Only show within 20km if searching "nearby"
                if (hasLocation) {
                    sql.append(" HAVING distance <= 20 ORDER BY distance ASC");
                }

                try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

                    for (int i = 0; i < sqlParams.size(); i++) {
                        stmt.setObject(i + 1, sqlParams.get(i));
                    }

                    ResultSet rs = stmt.executeQuery();
                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();

                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            String key = meta.getColumnLabel(i).toLowerCase();
                            Object val = rs.getObject(i);
                            
                            if ("pg_images".equals(key)) {
                                row.put("pg_images", buildImageCsv(val != null ? val.toString() : ""));
                            } else if ("pg_id".equals(key)) {
                                row.put("pg_id", val);
                                row.put("Hotel_ID", val); // Compatibility mapping
                            } else if ("pg_name".equals(key)) {
                                row.put("pg_name", val);
                                row.put("Hotel_Name", val); // Compatibility mapping
                            } else {
                                row.put(key, val);
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

    private String buildImageCsv(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String cleanRaw = raw.trim();
        if (cleanRaw.startsWith("[") && cleanRaw.endsWith("]")) {
            cleanRaw = cleanRaw.substring(1, cleanRaw.length() - 1);
        }
        String[] parts = cleanRaw.split(",");
        List<String> fixedUrls = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim().replace("\"", ""); 
            if (t.isEmpty()) continue;
            if (t.toLowerCase().startsWith("http")) {
                fixedUrls.add(t.replace("localhost", "10.0.2.2"));
            } else {
                fixedUrls.add("http://10.0.2.2:8080/hotel_images/" + t);
            }
        }
        return String.join(",", fixedUrls);
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length > 1) {
                params.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
        }
        return params;
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
        String err = "{\"error\":\"" + msg + "\"}";
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
            for (Map.Entry<String, Object> e : m.entrySet()) {
                sb.append("\"").append(e.getKey()).append("\":");
                Object value = e.getValue();
                if (value instanceof Number) {
                    sb.append(value);
                } else {
                    sb.append("\"").append(escape(String.valueOf(value))).append("\"");
                }
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
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}