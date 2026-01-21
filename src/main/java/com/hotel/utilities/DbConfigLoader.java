package com.hotel.utilities;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

public class DbConfigLoader {

    public static DbConfig load(String configPath) throws Exception {

        if (configPath == null || configPath.isBlank()) {
            throw new IllegalStateException(
                "Missing JVM argument: -Dconfig.path=/full/path/to/db.properties"
            );
        }

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configPath)) {
            props.load(fis);
        }

        // ===== Read DB properties =====
        String customerUrl   = props.getProperty("db.customerurl");
        String partnerUrl    = props.getProperty("db.partnerurl");
        String user          = props.getProperty("db.user");
        String encryptedPass = props.getProperty("db.pass");

        // ===== Read Image properties =====
        String imageBaseUrl    = props.getProperty("db.imagebaseurl");
        String hotelImagesPath = props.getProperty("db.hotelimagespath");

        // ===== Payment API properties =====
        String apiKey        = props.getProperty("db.apikey");
        String apiKeySecret  = props.getProperty("db.apikeysecret");
        String webHookSecret = props.getProperty("db.webhooksecret");

        // ===== Validate DB properties =====
        if (customerUrl == null || customerUrl.isBlank())
            throw new IllegalStateException("Missing property: db.customerurl");

        if (partnerUrl == null || partnerUrl.isBlank())
            throw new IllegalStateException("Missing property: db.partnerurl");

        if (user == null || user.isBlank())
            throw new IllegalStateException("Missing property: db.user");

        if (encryptedPass == null || encryptedPass.isBlank())
            throw new IllegalStateException("Missing property: db.pass");

        // ===== Validate Image properties =====
        if (imageBaseUrl == null || imageBaseUrl.isBlank())
            throw new IllegalStateException("Missing property: db.imagebaseurl");

        if (hotelImagesPath == null || hotelImagesPath.isBlank())
            throw new IllegalStateException("Missing property: db.hotelimagespath");

        // ===== Validate Payment API properties =====
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalStateException("Missing property: db.apikey");

        if (apiKeySecret == null || apiKeySecret.isBlank())
            throw new IllegalStateException("Missing property: db.apikeysecret");

        if (webHookSecret == null || webHookSecret.isBlank())
            throw new IllegalStateException("Missing property: db.webhooksecret");

        // ===== Normalize image base URL =====
        if (!imageBaseUrl.endsWith("/")) {
            imageBaseUrl += "/";
        }

        // ===== Normalize local image path =====
        if (!hotelImagesPath.endsWith(File.separator)) {
            hotelImagesPath += File.separator;
        }

        // ===== Validate local image directory =====
        File imageDir = new File(hotelImagesPath);
        if (!imageDir.exists() || !imageDir.isDirectory()) {
            throw new IllegalStateException(
                "Image directory does not exist or is not a directory: " + hotelImagesPath
            );
        }

        // ===== Decrypt DB password (CryptoUtil handles master key) =====
        String password = CryptoUtil.decrypt(encryptedPass);

        // ===== Return fully populated config =====
        return new DbConfig(
                customerUrl,
                partnerUrl,
                user,
                password,
                imageBaseUrl,
                hotelImagesPath,
                apiKey,
                apiKeySecret,
                webHookSecret
        );
    }
}
