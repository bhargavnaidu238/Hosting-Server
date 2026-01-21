package com.hotel.utilities;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

public class CryptoUtil {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;
    private static final int ITERATIONS = 65536;
    private static final int KEY_LENGTH = 256;

    // 🔐 DO NOT hardcode in real prod
    private static final String MASTER_KEY =
            System.getenv().getOrDefault("HOTEL_MASTER_KEY", "CHANGE_ME_NOW");

    private static SecretKeySpec getKey(byte[] salt) throws Exception {
        SecretKeyFactory factory =
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(
                MASTER_KEY.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    public static String encrypt(String plainText) throws Exception {
        byte[] salt = new byte[16];
        byte[] iv = new byte[IV_LENGTH];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, getKey(salt),
                new GCMParameterSpec(TAG_LENGTH, iv));

        byte[] cipherText =
                cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        return "ENC(" +
                Base64.getEncoder().encodeToString(salt) + ":" +
                Base64.getEncoder().encodeToString(iv) + ":" +
                Base64.getEncoder().encodeToString(cipherText) + ")";
    }

    public static String decrypt(String encrypted) throws Exception {
        encrypted = encrypted.replace("ENC(", "").replace(")", "");
        String[] parts = encrypted.split(":");

        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] iv = Base64.getDecoder().decode(parts[1]);
        byte[] cipherText = Base64.getDecoder().decode(parts[2]);

        Cipher cipher = Cipher.getInstance(ALGO);
        cipher.init(Cipher.DECRYPT_MODE, getKey(salt),
                new GCMParameterSpec(TAG_LENGTH, iv));

        return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    }
}
