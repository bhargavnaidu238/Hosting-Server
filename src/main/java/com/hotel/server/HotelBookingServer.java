package com.hotel.server;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.concurrent.Executors;

import com.hotel.app.AppFilterHandler;
import com.hotel.app.BookingHandler;
import com.hotel.app.BookingHistoryHandler;
import com.hotel.app.HomePageHandler;
import com.hotel.app.HotelsHandler;
import com.hotel.app.LoginHandler;
import com.hotel.app.PaymentHandler;
import com.hotel.app.PgsHandler;
import com.hotel.app.ProfileHandler;
import com.hotel.app.RegisterHandler;
import com.hotel.app.ReviewsHandler;
import com.hotel.app.RewardsWalletHandler;
import com.hotel.notification.service.EmailHandler;
import com.hotel.utilities.DbConfig;
import com.hotel.utilities.HotelImagesHandler;
import com.hotel.web.finance.GetPartnerFinanceHandler;
import com.hotel.web.finance.GetPartnerTransactionsHandler;
import com.hotel.web.finance.RequestPayoutHandler;
import com.hotel.web.finance.SetFinanceNotificationViewedHandler;
import com.hotel.web.finance.UpdateBankDetailsHandler;
import com.hotel.web.partner.AddHotelsHandler;
import com.hotel.web.partner.AddPgHandler;
import com.hotel.web.partner.WebBookingHandler;
import com.hotel.web.partner.WebDashBoardHandler;
import com.hotel.web.partner.WebLoginRegisterHandler;
import com.hotel.web.partner.WebProfileHandler;
import com.hotel.web.partner.WebViewHotelsHandler;
import com.hotel.web.partner.WebViewPGsHandler;
import com.sun.net.httpserver.HttpServer;

public class HotelBookingServer {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "10000"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        System.out.println("Server starting on port: " + port);

        DbConfig dbConfig = new DbConfig();
        try (Connection customerConn = dbConfig.getCustomerDataSource().getConnection();
             Connection partnerConn = dbConfig.getPartnerDataSource().getConnection()) {
            System.out.println("Database connections validated successfully");
        } catch (Exception e) {
            System.err.println("Database connection failed!");
            e.printStackTrace();
            System.exit(1);
        }

       // ========== MOBILE / APP HANDLERS ==========
        server.createContext("/login", new LoginHandler(dbConfig));
        server.createContext("/app/forgot-password/verify", new LoginHandler(dbConfig));
        server.createContext("/app/forgot-password/change", new LoginHandler(dbConfig));
        server.createContext("/register", new RegisterHandler(dbConfig));
        server.createContext("/hotels/filter", new HomePageHandler(dbConfig));
        server.createContext("/hotels", new HotelsHandler(dbConfig));
        server.createContext("/paying_guest", new PgsHandler(dbConfig));
        server.createContext("/booking", new BookingHandler(dbConfig));
        server.createContext("/profile", new ProfileHandler(dbConfig));
        server.createContext("/app/change-password", new ProfileHandler(dbConfig));
        server.createContext("/booking-history", new BookingHistoryHandler(dbConfig));
        server.createContext("/cancel-booking", new BookingHistoryHandler(dbConfig));
        server.createContext("/update-booking-dates", new BookingHistoryHandler(dbConfig));
        server.createContext("/filterHotels", new AppFilterHandler(dbConfig));
        
        // ========== WALLET & PAYMENTS ==========
        server.createContext("/wallet", new RewardsWalletHandler(dbConfig));
        server.createContext("/wallet/deposit", new RewardsWalletHandler(dbConfig));
        server.createContext("/wallet/pay", new RewardsWalletHandler(dbConfig));
        server.createContext("/coupon/validate", new RewardsWalletHandler(dbConfig));
        server.createContext("/referrals", new RewardsWalletHandler(dbConfig));
        server.createContext("/payment/createOrder", new PaymentHandler(dbConfig));
        server.createContext("/payment/verify", new PaymentHandler(dbConfig));
        server.createContext("/razorpay/webhook", new PaymentHandler(dbConfig));
        server.createContext("/payment/refund", new PaymentHandler(dbConfig));

        // ========== WEB ==========
        server.createContext("/weblogin", new WebLoginRegisterHandler(dbConfig));
        server.createContext("/registerlogin", new WebLoginRegisterHandler(dbConfig));
        server.createContext("/forgotpassword", new WebLoginRegisterHandler(dbConfig));

        server.createContext("/api/partner", new WebDashBoardHandler(dbConfig));

        server.createContext("/webgetprofile", new WebProfileHandler(dbConfig));
        server.createContext("/webupdateprofile", new WebProfileHandler(dbConfig));
        server.createContext("/webchangepassword", new WebProfileHandler(dbConfig));
        server.createContext("/webdeleteprofile", new WebProfileHandler(dbConfig));

        server.createContext("/webaddhotels", new AddHotelsHandler(dbConfig));
        server.createContext("/hotel_images", new HotelImagesHandler(dbConfig));
        server.createContext("/webaddpgs", new AddPgHandler(dbConfig));

        server.createContext("/webviewhotels", new WebViewHotelsHandler(dbConfig));
        server.createContext("/webviewpgs", new WebViewPGsHandler(dbConfig));

        server.createContext("/webgetPartnerBookings", new WebBookingHandler(dbConfig));
        server.createContext("/webcancelBooking", new WebBookingHandler(dbConfig));
        server.createContext("/webupdateBookingStatus", new WebBookingHandler(dbConfig));
        server.createContext("/setNotificationViewed", new SetFinanceNotificationViewedHandler(dbConfig));

        // ========== PARTNER FINANCE ==========
        server.createContext("/getPartnerFinance", new GetPartnerFinanceHandler(dbConfig));
        server.createContext("/updateBankDetails", new UpdateBankDetailsHandler(dbConfig));
        server.createContext("/requestPayout", new RequestPayoutHandler(dbConfig));
        server.createContext("/getPartnerTransactions", new GetPartnerTransactionsHandler(dbConfig));
        
     // ========== Notification Service ==========
        server.createContext("/send-email", new EmailHandler(dbConfig));
        server.createContext("/send-email-otp", new EmailHandler(dbConfig));
        server.createContext("/verify-email-otp", new EmailHandler(dbConfig));
        
        server.createContext("/reviews", new ReviewsHandler(dbConfig));



        server.setExecutor(Executors.newFixedThreadPool(20));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down server...");
            dbConfig.close();
            server.stop(1);
        }));

        server.start();
        System.out.println("Server started successfully on port " + port);
    }

    /**
     * ✅ SHARED SUPABASE UPLOAD LOGIC
     * Refined to handle Supabase Storage API v1 requirements.
     */
    public static String uploadToSupabase(byte[] imageBytes, String fileName) throws Exception {
        String supabaseUrl = System.getenv("SUPABASE_URL");
        String serviceKey = System.getenv("SUPABASE_SERVICE_ROLE_KEY");

        if (supabaseUrl == null || serviceKey == null) {
            throw new RuntimeException("Environment variables SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY are missing on Render");
        }

        if (supabaseUrl.endsWith("/")) {
            supabaseUrl = supabaseUrl.substring(0, supabaseUrl.length() - 1);
        }

        String bucketName = "hotels"; 

        URL url = new URL(supabaseUrl + "/storage/v1/object/" + bucketName + "/" + fileName);
        
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);

        conn.setRequestProperty("Authorization", "Bearer " + serviceKey);
        conn.setRequestProperty("Content-Type", "image/jpeg");
        conn.setRequestProperty("x-upsert", "true"); 

        // Stream bytes directly to the connection to save heap memory
        try (OutputStream os = conn.getOutputStream()) {
            os.write(imageBytes);
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == 200 || responseCode == 201) {
            return supabaseUrl + "/storage/v1/object/public/" + bucketName + "/" + fileName;
        } else {
            //FIX: Improved Error Handling to avoid memory leaks
            StringBuilder errorMsg = new StringBuilder();
            InputStream errorStream = conn.getErrorStream();
            if (errorStream != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) errorMsg.append(line);
                }
            }
            System.err.println("Supabase Error (" + responseCode + "): " + errorMsg.toString());
            throw new RuntimeException("Supabase upload failed with code: " + responseCode);
        }
    }
}