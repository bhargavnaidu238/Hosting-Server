package com.hotel.utilities;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

public final class DbConfig {

    // ===== ENV VALUES =====
    private final String customerDbUrl;
    private final String partnerDbUrl;
    private final String username;
    private final String password;

    private final String imageBaseUrl;
    private final String hotelImagesPath;

    private final String apiKey;
    private final String apiKeySecret;
    private final String webHookSecret;

    // ===== Lazy DataSources =====
    private volatile HikariDataSource customerDataSource;
    private volatile HikariDataSource partnerDataSource;

    // ===== Constructor =====
    public DbConfig() {
        this.customerDbUrl = normalizeJdbcUrl(getEnv("CUSTOMER_DB_URL"));
        this.partnerDbUrl  = normalizeJdbcUrl(getOptionalEnv("PARTNER_DB_URL"));

        this.username = getEnv("DB_USERNAME");
        this.password = getEnv("DB_PASSWORD");

        this.imageBaseUrl    = getEnv("IMAGE_BASE_URL");
        this.hotelImagesPath = getEnv("HOTEL_IMAGES_PATH");

        this.apiKey        = getEnv("PAYMENT_API_KEY");
        this.apiKeySecret  = getEnv("PAYMENT_API_SECRET");
        this.webHookSecret = getOptionalEnv("PAYMENT_WEBHOOK_SECRET");

        // ---- Startup visibility (VERY IMPORTANT) ----
        System.out.println("DB CONFIG LOADED");
        System.out.println("   CUSTOMER_DB_URL = " + customerDbUrl);
        if (partnerDbUrl != null) {
            System.out.println("   PARTNER_DB_URL  = " + partnerDbUrl);
        }
    }

    // ===== Lazy Init Getters =====
    public DataSource getCustomerDataSource() {
        if (customerDataSource == null) {
            synchronized (this) {
                if (customerDataSource == null) {
                    System.out.println("Initializing CUSTOMER DB pool");
                    customerDataSource = createDataSource(customerDbUrl);
                }
            }
        }
        return customerDataSource;
    }

    public DataSource getPartnerDataSource() {
        if (partnerDbUrl == null) {
            throw new IllegalStateException("PARTNER_DB_URL not configured");
        }
        if (partnerDataSource == null) {
            synchronized (this) {
                if (partnerDataSource == null) {
                    System.out.println("🔌 Initializing PARTNER DB pool");
                    partnerDataSource = createDataSource(partnerDbUrl);
                }
            }
        }
        return partnerDataSource;
    }

    // ===== Hikari Setup =====
    private HikariDataSource createDataSource(String jdbcUrl) {
        HikariConfig config = new HikariConfig();

        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");

        // ---- Render / Free-tier SAFE settings ----
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        // Fail fast instead of hanging forever
        config.setInitializationFailTimeout(10000);

        return new HikariDataSource(config);
    }

    // ===== JDBC URL NORMALIZER =====
    private String normalizeJdbcUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("JDBC URL is missing");
        }

        // Enforce sslmode=require for Render Postgres
        if (!url.contains("sslmode=")) {
            if (url.contains("?")) {
                url = url + "&sslmode=require";
            } else {
                url = url + "?sslmode=require";
            }
        }

        return url;
    }

    // ===== ENV HELPERS =====
    private String getEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("❌ Missing env var: " + key);
        }
        return value.trim();
    }

    private String getOptionalEnv(String key) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    // ===== Other Getters =====
    public String getImageBaseUrl() {
        return imageBaseUrl;
    }

    public String getHotelImagesPath() {
        return hotelImagesPath;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getAPIKeySecret() {
        return apiKeySecret;
    }

    public String getWebhookSecret() {
        return webHookSecret;
    }

    // ===== Shutdown =====
    public void close() {
        if (customerDataSource != null) {
            customerDataSource.close();
        }
        if (partnerDataSource != null) {
            partnerDataSource.close();
        }
    }
}
