package com.hotel.server;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.util.concurrent.Executors;

import com.hotel.app.*;
import com.hotel.utilities.DbConfig;
import com.hotel.web.finance.*;
import com.hotel.web.partner.*;
import com.sun.net.httpserver.HttpServer;

public class HotelBookingServer {

    public static void main(String[] args) throws Exception {

        // ✅ Render dynamic port
        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", "10000")
        );

        HttpServer server = HttpServer.create(
                new InetSocketAddress("0.0.0.0", port),
                0
        );

        System.out.println("🚀 Server starting on port: " + port);

        // ===== Initialize DB Config =====
        DbConfig dbConfig = new DbConfig();

        // ✅ Validate DB connections safely
        try (
                Connection customerConn = dbConfig.getCustomerDataSource().getConnection();
                Connection partnerConn = dbConfig.getPartnerDataSource().getConnection()
        ) {
            System.out.println("✅ Database connections validated successfully");
        } catch (Exception e) {
            System.err.println("❌ Database connection failed!");
            e.printStackTrace();
            System.exit(1);
        }

        // ===== Basic Health Endpoints =====
        server.createContext("/", exchange -> {
            String response = "Hotel Backend is running";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            exchange.getResponseBody().write(response.getBytes());
            exchange.close();
        });

        server.createContext("/health", exchange -> {
            String response = "OK";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            exchange.getResponseBody().write(response.getBytes());
            exchange.close();
        });

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

        // ✅ Use proper thread pool instead of default
        server.setExecutor(Executors.newFixedThreadPool(20));

        // ===== Graceful Shutdown =====
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("🛑 Shutting down server...");
            dbConfig.close();
            server.stop(1);
        }));

        server.start();

        System.out.println("✅ Server started successfully on port " + port);
    }
}
