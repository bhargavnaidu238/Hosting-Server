package com.hotel.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class HomePageHandler implements HttpHandler {

    private final DbConfig dbConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ✅ Inject DbConfig
    public HomePageHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        addCorsHeaders(exchange);

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, 405, "{\"error\":\"Only GET allowed\"}");
            return;
        }

        URI requestURI = exchange.getRequestURI();
        String query = requestURI.getQuery();

        String hotelType = null;
        String searchQuery = null;

        if (query != null && !query.isEmpty()) {
            for (String param : query.split("&")) {
                if (param.isBlank()) continue;
                String[] pair = param.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = pair.length > 1
                        ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                        : "";
                if ("type".equalsIgnoreCase(key)) {
                    hotelType = value.trim();
                } else if ("query".equalsIgnoreCase(key) || "q".equalsIgnoreCase(key)) {
                    searchQuery = value.trim();
                }
            }
        }

        String normalizedType = hotelType == null
                ? ""
                : hotelType.replaceAll("[_\\-\\s]", "").toLowerCase();

        if ("payingguest".equals(normalizedType)) {
            handlePayingGuestRequest(exchange, searchQuery);
            return;
        }

        handleHotelRequest(exchange, hotelType, searchQuery);
    }

    // =================== HOTELS ===================
    private void handleHotelRequest(HttpExchange exchange,
                                    String hotelType,
                                    String searchQuery) throws IOException {

        List<Map<String, Object>> hotels = new ArrayList<>();

        String baseSql = """
            SELECT hotel_id, partner_id, hotel_name, hotel_type, room_type,
                   address, city, state, country, pincode, hotel_location,
                   total_rooms, available_rooms, room_Price, amenities,
                   description, policies, rating, hotel_contact,
                   about_this_property, hotel_images, customization, status
            FROM hotels_info
            WHERE status = 'Active'
            """;

        StringBuilder sql = new StringBuilder(baseSql);

        if (hotelType != null && !hotelType.isBlank()) {
            sql.append(" AND LOWER(hotel_type) = LOWER(?)");
        }

        if (searchQuery != null && !searchQuery.isBlank()) {
            sql.append("""
                AND (
                    LOWER(hotel_name) LIKE ?
                    OR LOWER(city) LIKE ?
                    OR LOWER(state) LIKE ?
                    OR LOWER(country) LIKE ?
                )
            """);
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            int idx = 1;
            if (hotelType != null && !hotelType.isBlank()) {
                stmt.setString(idx++, hotelType);
            }
            if (searchQuery != null && !searchQuery.isBlank()) {
                String p = "%" + searchQuery.toLowerCase() + "%";
                stmt.setString(idx++, p);
                stmt.setString(idx++, p);
                stmt.setString(idx++, p);
                stmt.setString(idx++, p);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> hotel = new LinkedHashMap<>();

                    hotel.put("hotel_id", rs.getString("hotel_id"));
                    hotel.put("partner_id", rs.getString("partner_id"));
                    hotel.put("hotel_name", rs.getString("hotel_name"));
                    hotel.put("hotel_type", rs.getString("hotel_type"));
                    hotel.put("room_type", rs.getString("Room_Type"));
                    hotel.put("address", rs.getString("Address"));
                    hotel.put("city", rs.getString("City"));
                    hotel.put("state", rs.getString("State"));
                    hotel.put("country", rs.getString("Country"));
                    hotel.put("pincode", rs.getString("Pincode"));
                    hotel.put("hotel_location", rs.getString("Hotel_Location"));
                    hotel.put("total_rooms", rs.getObject("Total_Rooms"));
                    hotel.put("available_rooms", rs.getObject("Available_Rooms"));
                    hotel.put("room_price", rs.getObject("Room_Price"));
                    hotel.put("amenities", rs.getString("Amenities"));
                    hotel.put("description", rs.getString("Description"));
                    hotel.put("policies", rs.getString("Policies"));
                    hotel.put("rating", rs.getObject("Rating"));
                    hotel.put("hotel_contact", rs.getString("Hotel_Contact"));
                    hotel.put("about_this_property", rs.getString("About_This_Property"));
                    hotel.put("customization", rs.getString("Customization"));
                    hotel.put("status", rs.getString("Status"));

                    hotel.put("Hotel_Images",
                            buildImageList(rs.getString("hotel_images")));

                    hotels.add(hotel);
                }
            }

            sendJsonResponse(exchange, 200,
                    objectMapper.writeValueAsString(hotels));

        } catch (Exception e) {
            e.printStackTrace();
            sendJsonResponse(exchange, 500,
                    "{\"error\":\"Internal error\"}");
        }
    }

    // =================== PAYING GUEST ===================
    private void handlePayingGuestRequest(HttpExchange exchange,
                                          String searchQuery) throws IOException {

        List<Map<String, Object>> pgs = new ArrayList<>();

        String baseSql = """
            SELECT pd_id, partner_id, pg_name, pg_type, room_type,
                   address, city, state, country, pincode,
                   total_single_sharing_rooms,
                   total_double_sharing_rooms,
                   total_three_sharing_rooms,
                   total_four_sharing_rooms,
                   total_five_sharing_rooms,
                   hotel_location, available_rooms, room_price,
                   amenities, description, policies, rating,
                   pg_contact, about_this_pg, pg_images, status
            FROM paying_guest_info
            WHERE status = 'Active'
        """;

        StringBuilder sql = new StringBuilder(baseSql);

        if (searchQuery != null && !searchQuery.isBlank()) {
            sql.append("""
                AND (
                    LOWER(pg_name) LIKE ?
                    OR LOWER(city) LIKE ?
                    OR LOWER(state) LIKE ?
                    OR LOWER(country) LIKE ?
                )
            """);
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            if (searchQuery != null && !searchQuery.isBlank()) {
                String p = "%" + searchQuery.toLowerCase() + "%";
                stmt.setString(1, p);
                stmt.setString(2, p);
                stmt.setString(3, p);
                stmt.setString(4, p);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> pg = new LinkedHashMap<>();

                    pg.put("pg_id", rs.getString("pg_id"));
                    pg.put("partner_id", rs.getString("partner_id"));
                    pg.put("pg_name", rs.getString("pg_name"));
                    pg.put("pg_type", rs.getString("pg_type"));
                    pg.put("room_type", rs.getString("room_type"));
                    pg.put("address", rs.getString("address"));
                    pg.put("city", rs.getString("city"));
                    pg.put("state", rs.getString("state"));
                    pg.put("country", rs.getString("country"));
                    pg.put("pincode", rs.getString("pincode"));

                    pg.put("total_single_sharing_rooms", rs.getObject("total_single_sharing_rooms"));
                    pg.put("total_double_sharing_rooms", rs.getObject("total_double_sharing_rooms"));
                    pg.put("total_four_sharing_rooms", rs.getObject("total_four_sharing_rooms"));
                    pg.put("total_four_sharing_rooms", rs.getObject("total_four_sharing_rooms"));
                    pg.put("total_five_sharing_rooms", rs.getObject("total_five_sharing_rooms"));

                    pg.put("hotel_location", rs.getString("hotel_location"));
                    pg.put("available_rooms", rs.getObject("available_rooms"));
                    pg.put("room_price", rs.getObject("room_price"));
                    pg.put("amenities", rs.getString("amenities"));
                    pg.put("description", rs.getString("description"));
                    pg.put("policies", rs.getString("policies"));
                    pg.put("rating", rs.getObject("rating"));
                    pg.put("pg_contact", rs.getString("pg_contact"));
                    pg.put("about_this_pg", rs.getString("about_this_pg"));
                    pg.put("status", rs.getString("status"));
                    pg.put("pg_images", buildImageList(rs.getString("pg_images")));

                    pgs.add(pg);
                }
            }

            sendJsonResponse(exchange, 200,
                    objectMapper.writeValueAsString(pgs));

        } catch (Exception e) {
            e.printStackTrace();
            sendJsonResponse(exchange, 500,
                    "{\"error\":\"Internal error\"}");
        }
    }

    // =================== HELPERS ===================
    private List<String> buildImageList(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.isBlank()) return list;

        for (String p : raw.split(",")) {
            String t = p.trim();
            if (t.isEmpty()) continue;

            if (t.startsWith("http://") || t.startsWith("https://")) {
                list.add(t);
            } else {
                list.add(dbConfig.getImageBaseUrl()
                        + t.replaceAll("^/+", ""));
            }
        }
        return list;
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private void sendJsonResponse(HttpExchange exchange, int status, String json)
            throws IOException {

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
