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

public class HotelsHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public HotelsHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
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
            if (path.startsWith("/hotel_images/")) {
                String fileName = path.substring("/hotel_images/".length());
                serveImage(exchange, fileName);
                return;
            }

            if (path.startsWith("/hotels")) {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Method Not Allowed");
                    return;
                }

                String query = uri.getQuery();
                String typeFilter = null;
                boolean detailsPage = false;
                String hotelId = null;

                if (query != null) {
                    Map<String, String> params = parseQueryParams(query);
                    typeFilter = params.get("type");
                    hotelId = params.get("hotel_id");
                    if (hotelId != null) detailsPage = true;
                }

                List<Map<String, Object>> hotels = new ArrayList<>();

                // FIXED: Removed 'description' column to match updated schema
                String sql = "SELECT hotel_id, partner_id, hotel_name, hotel_type, room_type, address, city, state, country, " +
                        "pincode, hotel_location, total_rooms, available_rooms, room_price, amenities, " +
                        "policies, rating, hotel_contact, about_this_property, hotel_images, customization, status " +
                        "FROM hotels_info WHERE status = 'Active'";

                if (typeFilter != null && !typeFilter.trim().isEmpty()) {
                    sql += " AND hotel_type = ?";
                }
                if (detailsPage && hotelId != null) {
                    sql += " AND hotel_id = ?";
                }

                try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql)) {

                    int paramIndex = 1;
                    if (typeFilter != null && !typeFilter.trim().isEmpty()) {
                        stmt.setString(paramIndex++, typeFilter);
                    }
                    if (detailsPage && hotelId != null) {
                        stmt.setString(paramIndex++, hotelId);
                    }

                    ResultSet rs = stmt.executeQuery();
                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();

                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            String key = meta.getColumnLabel(i);
                            Object val = rs.getObject(i);
                            
                            if ("hotel_images".equalsIgnoreCase(key)) {
                                row.put("Hotel_Images", buildImageCsv(rs.getString("hotel_images")));
                            } else {
                                row.put(key, val == null ? "" : val);
                            }
                        }
                        hotels.add(row);
                    }
                    sendJson(exchange, hotels);
                }
                return;
            }

            sendError(exchange, 404, "Unknown endpoint: " + path);
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    private String buildImageCsv(String raw) {
        if (raw == null || raw.isBlank()) return "";
        
        String[] parts = raw.split(",");
        List<String> fixedUrls = new ArrayList<>();
        
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            
            if (t.toLowerCase().startsWith("http")) {
                fixedUrls.add(t);
            } else {
                fixedUrls.add("http://10.0.2.2:8080/hotel_images/" + t);
            }
        }
        return String.join(",", fixedUrls);
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
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
                sb.append("\"").append(escape(e.getKey())).append("\":\"");
                sb.append(escape(String.valueOf(e.getValue()))).append("\"");
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