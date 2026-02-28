package com.hotel.utilities;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class HotelImagesHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public HotelImagesHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        // ================= CORS =================
        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        // ======================================================
        // ================= SUPABASE CONFIG ====================
        // ======================================================
        if ("/config".equalsIgnoreCase(path)) {

            String jsonResponse = "{"
                    + "\"supabaseUrl\":\"" + dbConfig.getSupabaseUrl() + "\","
                    + "\"anonKey\":\"" + dbConfig.getAnonKey() + "\""
                    + "}";

            byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

            exchange.sendResponseHeaders(200, responseBytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
            return;
        }

        // ======================================================
        // ================= SUPABASE IMAGE UPLOAD ==============
        // ======================================================
        if (path.startsWith("/upload") && "POST".equalsIgnoreCase(method)) {

            String supabaseUrl = System.getenv("SUPABASE_URL");
            String serviceKey = System.getenv("SUPABASE_SERVICE_ROLE_KEY");

            if (supabaseUrl == null || serviceKey == null) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }

            byte[] imageBytes = exchange.getRequestBody().readAllBytes();

            String fileName = UUID.randomUUID() + ".jpg";

            String uploadUrl = supabaseUrl
                    + "/storage/v1/object/FleminGolmages/"
                    + fileName;

            URL url = new URL(uploadUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);

            conn.setRequestProperty("Authorization", "Bearer " + serviceKey);
            conn.setRequestProperty("Content-Type", "image/jpeg");
            conn.setRequestProperty("x-upsert", "true");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(imageBytes);
            }

            int responseCode = conn.getResponseCode();

            if (responseCode == 200 || responseCode == 201) {

                String publicUrl = supabaseUrl
                        + "/storage/v1/object/public/FleminGolmages/"
                        + fileName;

                String json = "{ \"url\": \"" + publicUrl + "\" }";

                byte[] resp = json.getBytes(StandardCharsets.UTF_8);

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

                exchange.sendResponseHeaders(200, resp.length);

                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }

            } else {
                exchange.sendResponseHeaders(500, -1);
            }

            return;
        }

        // ======================================================
        // ================= LOCAL IMAGE SERVING (LEGACY) =======
        // ======================================================

        if (!"GET".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        if (!path.startsWith("/hotel_images/")) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }

        String relativePath = URLDecoder.decode(
                path.substring("/hotel_images/".length()),
                StandardCharsets.UTF_8
        );

        Path imageRoot = Path.of(dbConfig.getHotelImagesPath()).normalize();
        Path resolvedPath = imageRoot.resolve(relativePath).normalize();

        if (!resolvedPath.startsWith(imageRoot)) {
            exchange.sendResponseHeaders(403, -1);
            return;
        }

        File file = resolvedPath.toFile();

        if (!file.exists() || file.isDirectory()) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }

        String contentType = Files.probeContentType(file.toPath());
        if (contentType == null) contentType = "application/octet-stream";

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

        exchange.sendResponseHeaders(200, file.length());

        try (OutputStream os = exchange.getResponseBody();
             FileInputStream fis = new FileInputStream(file)) {
            fis.transferTo(os);
        }
    }
}