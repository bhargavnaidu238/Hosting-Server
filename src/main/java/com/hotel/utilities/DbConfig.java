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
    
    private final String supabaseurl;
    private final String anonKey;
    
    // ===== EMAIL (ZOHO SMTP) =====
    private final String smtpHost;
    private final String smtpPort;
    private final String smtpUsername;
    private final String smtpPassword;
    private final String senderEmail;
    private final String senderName;

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
        
        this.supabaseurl = getEnv("SUPABASE_URL");
        this.anonKey     = getEnv("ANON_KEY");

        // ===== ZOHO SMTP ENV =====
        this.smtpHost     = getEnv("SMTP_HOST");
        this.smtpPort     = getEnv("SMTP_PORT");
        this.smtpUsername = getEnv("SMTP_USERNAME");
        this.smtpPassword = getEnv("SMTP_PASSWORD");

        this.senderEmail = getEnv("SENDER_EMAIL");
        this.senderName  = getOptionalEnv("SENDER_NAME"); // optional

        System.out.println("DB CONFIG LOADED");
        System.out.println("CUSTOMER_DB_URL = " + customerDbUrl);
        if (partnerDbUrl != null) {
            System.out.println("PARTNER_DB_URL  = " + partnerDbUrl);
        }
    }

    // ===== Lazy Init =====
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
                    System.out.println("Initializing PARTNER DB pool");
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

        config.addDataSourceProperty("prepareThreshold", "0");

        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        config.setLeakDetectionThreshold(30000);
        config.setInitializationFailTimeout(10000);

        return new HikariDataSource(config);
    }

    // ===== JDBC URL NORMALIZER =====
    private String normalizeJdbcUrl(String url) {

        if (url == null || url.isBlank()) {
            throw new IllegalStateException("JDBC URL is missing");
        }

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
            throw new IllegalStateException("Missing env var: " + key);
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
    
    public String getSupabaseUrl() {
        return supabaseurl;
    }

    public String getAnonKey() {
        return anonKey;
    }

    // ===== EMAIL GETTERS =====
    public String getSmtpHost() {
        return smtpHost;
    }

    public String getSmtpPort() {
        return smtpPort;
    }

    public String getSmtpUsername() {
        return smtpUsername;
    }

    public String getSmtpPassword() {
        return smtpPassword;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public String getSenderName() {
        return senderName;
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