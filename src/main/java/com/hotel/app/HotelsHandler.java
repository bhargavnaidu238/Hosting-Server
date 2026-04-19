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
        // Handle CORS
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
            // Serve Images
            if (path.startsWith("/hotel_images/")) {
                String fileName = path.substring("/hotel_images/".length());
                serveImage(exchange, fileName);
                return;
            }

            // Handle Hotels Metadata
            if (path.startsWith("/hotels")) {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Method Not Allowed");
                    return;
                }

                String query = uri.getQuery();
                Map<String, String> params = parseQueryParams(query);
                
                String typeFilter = params.get("type");
                String hotelId = params.get("hotel_id");
                String cityFilter = params.get("city");
                String searchFilter = params.get("search");

                List<Map<String, Object>> hotels = new ArrayList<>();

                // Build SQL Query Dynamically
                StringBuilder sql = new StringBuilder("SELECT * FROM hotels_info WHERE status = 'Active'");
                List<Object> sqlParams = new ArrayList<>();

                if (typeFilter != null && !typeFilter.trim().isEmpty() && !typeFilter.equalsIgnoreCase("All")) {
                    sql.append(" AND hotel_type = ?");
                    sqlParams.add(typeFilter);
                }
                
                if (hotelId != null && !hotelId.trim().isEmpty()) {
                    sql.append(" AND Hotel_ID = ?");
                    sqlParams.add(hotelId);
                }

                if (cityFilter != null && !cityFilter.trim().isEmpty()) {
                    sql.append(" AND LOWER(city) = ?");
                    sqlParams.add(cityFilter.toLowerCase().trim());
                }

                if (searchFilter != null && !searchFilter.trim().isEmpty()) {
                    sql.append(" AND (LOWER(Hotel_Name) LIKE ? OR LOWER(city) LIKE ?)");
                    String pattern = "%" + searchFilter.toLowerCase().trim() + "%";
                    sqlParams.add(pattern);
                    sqlParams.add(pattern);
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
                            String key = meta.getColumnLabel(i);
                            Object val = rs.getObject(i);
                            String valueStr = (val == null) ? "" : val.toString();

                            // Standardization for Flutter Frontend Keys
                            if (key.equalsIgnoreCase("hotel_name")) {
                                row.put("Hotel_Name", valueStr);
                            } else if (key.equalsIgnoreCase("room_price")) {
                                row.put("Room_Price", valueStr);
                            } else if (key.equalsIgnoreCase("hotel_images")) {
                                row.put("Hotel_Images", buildImageCsv(valueStr));
                            } else if (key.equalsIgnoreCase("rating") || key.equalsIgnoreCase("avg_rating")) {
                                row.put("Rating", valueStr);
                            } else if (key.equalsIgnoreCase("total_reviews")) {
                                row.put("total_reviews", valueStr);
                            } else {
                                row.put(key, valueStr);
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
                // Using standard Android Emulator local IP
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
            } else if (pair.length == 1) {
                params.put(pair[0], "");
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
            for (Map.Entry<String, Object> e : m.entrySet()) {
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
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\t", " ");
    }
}