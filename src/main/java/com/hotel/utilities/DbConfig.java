package com.hotel.utilities;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;


public final class DbConfig {

    // ===== DB URLs =====
    private final String customerDbUrl;
    private final String partnerDbUrl;

    // ===== Credentials =====
    private final String username;
    private final String password;

    // ===== Image Config =====
    private final String imageBaseUrl;
    private final String hotelImagesPath;

    // ===== Payment API Config =====
    private final String apiKey;
    private final String apiKeySecret;
    private final String webHookSecret;

    // ===== DataSources =====
    private final HikariDataSource customerDataSource;
    private final HikariDataSource partnerDataSource;

    // ===== Constructor (ENV BASED) =====
    public DbConfig() {

        this.customerDbUrl = getEnv("CUSTOMER_DB_URL");
        this.partnerDbUrl  = getEnv("PARTNER_DB_URL");
        this.username      = getEnv("DB_USERNAME");
        this.password      = getEnv("DB_PASSWORD");

        this.imageBaseUrl      = getEnv("IMAGE_BASE_URL");
        this.hotelImagesPath   = getEnv("HOTEL_IMAGES_PATH");

        this.apiKey        = getEnv("PAYMENT_API_KEY");
        this.apiKeySecret  = getEnv("PAYMENT_API_SECRET");
        this.webHookSecret = getOptionalEnv("PAYMENT_WEBHOOK_SECRET");
        //this.webHookSecret = getEnv("PAYMENT_WEBHOOK_SECRET");

        // Initialize pools
        this.customerDataSource = createDataSource(customerDbUrl);
        this.partnerDataSource  = createDataSource(partnerDbUrl);
    }
    
    private String getOptionalEnv(String key) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? null : value;
    }


    // ===== ENV Helper =====
    private String getEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Missing required environment variable: " + key
            );
        }
        return value;
    }


    // ===== HikariCP Setup =====
    private HikariDataSource createDataSource(String jdbcUrl) {

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);

        // Explicit driver (MySQL)
        //config.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // Pool tuning (safe defaults)
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30_000);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);

        // MySQL optimizations
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        return new HikariDataSource(config);
    }

    // ===== Public Accessors =====
    public DataSource getCustomerDataSource() {
        return customerDataSource;
    }

    public DataSource getPartnerDataSource() {
        return partnerDataSource;
    }

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

    // ===== Graceful Shutdown =====
    public void close() {
        if (customerDataSource != null && !customerDataSource.isClosed()) {
            customerDataSource.close();
        }
        if (partnerDataSource != null && !partnerDataSource.isClosed()) {
            partnerDataSource.close();
        }
    }
}
