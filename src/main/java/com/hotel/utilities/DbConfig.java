package com.hotel.utilities;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

public final class DbConfig {

    // ===== CONFIG VALUES (IMMUTABLE) =====
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

    // ===== Constructor (USED BY DbConfigLoader) =====
    public DbConfig(
            String customerDbUrl,
            String partnerDbUrl,
            String username,
            String password,
            String imageBaseUrl,
            String hotelImagesPath,
            String apiKey,
            String apiKeySecret,
            String webHookSecret
    ) {
        this.customerDbUrl = normalizeJdbcUrl(customerDbUrl);
        this.partnerDbUrl  = partnerDbUrl != null
                ? normalizeJdbcUrl(partnerDbUrl)
                : null;

        this.username = username;
        this.password = password;

        this.imageBaseUrl = imageBaseUrl;
        this.hotelImagesPath = hotelImagesPath;

        this.apiKey = apiKey;
        this.apiKeySecret = apiKeySecret;
        this.webHookSecret = webHookSecret;

        // ---- Startup visibility ----
        System.out.println("✅ DB CONFIG LOADED");
        System.out.println("   CUSTOMER_DB_URL = " + this.customerDbUrl);
        if (this.partnerDbUrl != null) {
            System.out.println("   PARTNER_DB_URL  = " + this.partnerDbUrl);
        }
    }

    // ===== Lazy Init Getters =====
    public DataSource getCustomerDataSource() {
        if (customerDataSource == null) {
            synchronized (this) {
                if (customerDataSource == null) {
                    System.out.println("🔌 Initializing CUSTOMER DB pool");
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

        // ---- Free-tier / Render-safe ----
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(30_000);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);
        config.setInitializationFailTimeout(10_000);

        return new HikariDataSource(config);
    }

    // ===== JDBC URL NORMALIZER =====
    private String normalizeJdbcUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("JDBC URL is missing");
        }

        if (!url.contains("sslmode=")) {
            url += url.contains("?") ? "&sslmode=require" : "?sslmode=require";
        }
        return url;
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

    public String getApiKeySecret() {
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
