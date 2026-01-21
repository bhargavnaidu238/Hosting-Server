package com.hotel.utilities;

public class EncryptTool {

    public static void main(String[] args) throws Exception {

        String plainPassword = "your_db_password_here";

        String encrypted = CryptoUtil.encrypt(plainPassword);
        System.out.println(encrypted);
    }
}
