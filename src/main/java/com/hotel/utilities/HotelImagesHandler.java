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
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        // ================= SUPABASE CONFIG ====================
        if ("/config".equalsIgnoreCase(path)) {
            String jsonResponse = "{"
                    + "\"supabaseUrl\":\"" + dbConfig.getSupabaseUrl() + "\","
                    + "\"anonKey\":\"" + dbConfig.getAnonKey() + "\""
                    + "}";

            byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
            return;
        }

        // ================= SUPABASE IMAGE UPLOAD ==============
        if (path.startsWith("/upload") && "POST".equalsIgnoreCase(method)) {

            String supabaseUrl = System.getenv("SUPABASE_URL");
            String serviceKey = System.getenv("SUPABASE_SERVICE_ROLE_KEY");

            if (supabaseUrl == null || serviceKey == null) {
                System.err.println("Upload Failed: Supabase Environment variables are missing.");
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            if (supabaseUrl.endsWith("/")) {
                supabaseUrl = supabaseUrl.substring(0, supabaseUrl.length() - 1);
            }

            byte[] imageBytes = exchange.getRequestBody().readAllBytes();
            String fileName = UUID.randomUUID() + ".jpg";

            String bucketName = "hotels";

            String uploadUrl = supabaseUrl + "/storage/v1/object/" + bucketName + "/" + fileName;

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
                String publicUrl = supabaseUrl + "/storage/v1/object/public/" + bucketName + "/" + fileName;
                String json = "{ \"url\": \"" + publicUrl + "\" }";
                byte[] resp = json.getBytes(StandardCharsets.UTF_8);

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);

                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            } else {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) errorResponse.append(line);
                    System.err.println("Supabase Upload Error (" + responseCode + "): " + errorResponse.toString());
                }
                exchange.sendResponseHeaders(responseCode, -1);
            }
            return;
        }

        // ================= LOCAL IMAGE SERVING (LEGACY) =======
        if (!"GET".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        if (path.startsWith("/hotel_images/")) {
            String relativePath = URLDecoder.decode(
                    path.substring("/hotel_images/".length()),
                    StandardCharsets.UTF_8
            );

            Path imageRoot = Path.of(dbConfig.getHotelImagesPath()).normalize();
            Path resolvedPath = imageRoot.resolve(relativePath).normalize();

            // Prevent Path Traversal Attacks
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
            exchange.sendResponseHeaders(200, file.length());

            try (OutputStream os = exchange.getResponseBody();
                 FileInputStream fis = new FileInputStream(file)) {
                fis.transferTo(os);
            }
            return;
        }
        exchange.sendResponseHeaders(404, -1);
    }
}