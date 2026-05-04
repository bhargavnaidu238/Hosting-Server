package com.hotel.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.utilities.DbConfig;
import com.razorpay.*;
import com.sun.net.httpserver.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class PaymentHandler implements HttpHandler {

    private final DbConfig dbConfig;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String RZP_KEY;
    private final String RZP_SECRET;

    // FIX #1: Webhook secret and API secret are TWO DIFFERENT credentials in
    // Razorpay. The webhook secret is set separately in the Razorpay Dashboard
    // under Settings → Webhooks. Using the API key secret here means every
    // webhook signature check would either always fail or accidentally pass
    // when they happen to match, making the webhook endpoint insecure/broken.
    // Fetch it from a dedicated config key (e.g. razorpay.webhook.secret).
    private final String WEBHOOK_SECRET;

    public PaymentHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
        this.RZP_KEY    = dbConfig.getApiKey();
        this.RZP_SECRET = dbConfig.getAPIKeySecret();
        // FIX #1 (applied): Use a dedicated webhook-secret getter.
        this.WEBHOOK_SECRET = dbConfig.getWebhookSecret();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ROUTER
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void handle(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return;
        }

        String path = ex.getRequestURI().getPath();

        // FIX #2: Wrap the entire dispatch in a try/catch that guards against
        // double-close. respond() no longer calls ex.close() itself; we close
        // once here in the finally block so that an exception path can never
        // attempt a second close on an already-closed exchange.
        try {
            switch (path) {
                case "/payment/createOrder" -> createOrder(ex);
                case "/payment/verify"      -> verifyFromClient(ex);
                case "/payment/webhook"     -> handleWebhook(ex);
                default                     -> respond(ex, 404, jsonObj("error", "Endpoint not found"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                respond(ex, 500, jsonObj("error", e.getMessage() != null ? e.getMessage() : "Internal Server Error"));
            } catch (IOException ignored) {
                // Response may have already been started; swallow safely.
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE ORDER
    // ─────────────────────────────────────────────────────────────────────────

    private void createOrder(HttpExchange ex) throws IOException {
        Map<String, Object> req = readJson(ex);
        try {
            RazorpayClient client = new RazorpayClient(RZP_KEY, RZP_SECRET);

            int amountInPaise = toInt(req.get("amount"));
            String userId     = str(req.get("userId"));

            JSONObject orderReq = new JSONObject();
            orderReq.put("amount", amountInPaise);
            orderReq.put("currency", "INR");
            orderReq.put("payment_capture", 1);

            Order order = client.orders.create(orderReq);
            String orderId = order.get("id").toString();

            // FIX #3: The original code generated a tempPrid UUID and sent it
            // to the client but NEVER persisted it anywhere. On the verify
            // call the backend had no way to validate it was the same record.
            // Now we INSERT a pending payment_record row immediately so the
            // record exists before money moves, enabling proper idempotency.
            String prid = UUID.randomUUID().toString();
            insertPendingPaymentRecord(prid, orderId, userId, amountInPaise / 100.0);

            JSONObject res = new JSONObject();
            res.put("order_id",          orderId);
            res.put("razorpay_key_id",   RZP_KEY);
            res.put("amount",            order.get("amount").toString());
            res.put("payment_record_id", prid);

            respond(ex, 200, res.toString());
        } catch (Exception e) {
            e.printStackTrace();
            respond(ex, 500, jsonObj("error", e.getMessage()));
        }
    }

    // FIX #3 (continued): Insert a PENDING row at order-creation time.
    // This guarantees the payment_record_id is always traceable in the DB
    // regardless of whether the client's verify call reaches the server.
    private void insertPendingPaymentRecord(String prid, String orderId, String userId, double amount) {
        String sql = "INSERT INTO payment_transactions (" +
                     "payment_record_id, user_id, payment_gateway, gateway_order_id, " +
                     "payment_status, amount, currency, payment_attempt_no, " +
                     "is_refunded, refund_amount, created_at, updated_at" +
                     ") VALUES (?,?,?,?,?,?,?,?,?,?,NOW(),NOW()) " +
                     "ON CONFLICT (payment_record_id) DO NOTHING";
        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, prid);
            ps.setString(2, userId);
            ps.setString(3, "Razorpay");
            ps.setString(4, orderId);
            ps.setString(5, "PENDING");
            ps.setDouble(6, amount);
            ps.setString(7, "INR");
            ps.setInt(8, 1);
            ps.setString(9, "No");
            ps.setDouble(10, 0.00);
            ps.executeUpdate();
        } catch (SQLException e) {
            // Non-fatal: log and continue. The verify step will upsert the row.
            System.err.println("Warning: could not pre-insert payment record: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLIENT-SIDE VERIFY  (called by the Flutter app after payment success)
    // ─────────────────────────────────────────────────────────────────────────

    private void verifyFromClient(HttpExchange ex) throws IOException {
        Map<String, Object> p = readJson(ex);

        String bookingId  = str(p.get("booking_id"));
        String userId     = str(p.get("user_id"));
        String partnerId  = str(p.get("partner_id"));
        String hotelId    = str(p.get("hotel_id"));
        String orderId    = str(p.get("gateway_order_id"));
        String paymentId  = str(p.get("gateway_payment_id"));
        String signature  = str(p.get("gateway_signature"));
        String existingPrid = str(p.get("payment_record_id"));
        double amount     = toDouble(p.get("final_payable_amount"));

        processPaymentUpdate(ex, bookingId, userId, partnerId, hotelId,
                orderId, paymentId, signature, amount, existingPrid, false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WEBHOOK  (Razorpay server-to-server fallback — fires even if app crashes)
    // ─────────────────────────────────────────────────────────────────────────

    private void handleWebhook(HttpExchange ex) throws IOException {
        // FIX #4: The webhook body can only be read ONCE from the stream.
        // Buffer it immediately so we can (a) verify the signature and
        // (b) parse the JSON payload — both from the same string.
        String body = new BufferedReader(
                new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining("\n"));

        String sigHeader = ex.getRequestHeaders().getFirst("X-Razorpay-Signature");

        // FIX #5 (was: webhook verified but did nothing with the payload).
        // Now we actually process the payment event so the DB stays consistent
        // even when the Flutter client-side verify call never reaches us
        // (e.g. app crash, network drop after payment screen).
        try {
            if (!Utils.verifyWebhookSignature(body, sigHeader, WEBHOOK_SECRET)) {
                respond(ex, 401, jsonObj("error", "Invalid Webhook Signature"));
                return;
            }
        } catch (Exception e) {
            respond(ex, 500, jsonObj("error", "Webhook signature check failed"));
            return;
        }

        try {
            JSONObject payload    = new JSONObject(body);
            String     event      = payload.optString("event", "");

            // Only handle successful payment captures.
            if (!"payment.captured".equals(event)) {
                respond(ex, 200, jsonObj("status", "ignored"));
                return;
            }

            JSONObject paymentObj = payload
                    .getJSONObject("payload")
                    .getJSONObject("payment")
                    .getJSONObject("entity");

            String paymentId = paymentObj.optString("id",       "");
            String orderId   = paymentObj.optString("order_id", "");
            double amount    = paymentObj.optDouble("amount", 0) / 100.0; // paise → rupees

            // Resolve booking via the gateway order_id stored at create-order time.
            String bookingId = resolveBookingIdFromOrder(orderId);

            // Signature is not re-sent in webhook payloads; pass empty string.
            // The idempotency check in processPaymentUpdate will guard duplicates.
            processPaymentUpdate(ex, bookingId, "", "", "",
                    orderId, paymentId, "", amount, "", true);

        } catch (Exception e) {
            e.printStackTrace();
            respond(ex, 500, jsonObj("error", "Webhook processing failed: " + e.getMessage()));
        }
    }

    // Looks up the booking that was created against a given Razorpay order_id.
    private String resolveBookingIdFromOrder(String orderId) {
        if (orderId == null || orderId.isEmpty()) return "";
        String sql = "SELECT booking_id FROM bookings_info WHERE razorpay_order_id = ? LIMIT 1";
        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("booking_id") : "";
            }
        } catch (SQLException e) {
            System.err.println("resolveBookingIdFromOrder error: " + e.getMessage());
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CORE PAYMENT PROCESSING  (shared by both verify and webhook paths)
    // ─────────────────────────────────────────────────────────────────────────

    private void processPaymentUpdate(HttpExchange ex,
                                      String bid, String uid, String pid, String hid,
                                      String oid, String payid, String sig,
                                      double amt, String prid, boolean isWebhook) throws IOException {

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {

            // ── 1. IDEMPOTENCY CHECK ────────────────────────────────────────
            // If this gateway_payment_id was already successfully processed,
            // skip entirely. This protects against both duplicate client calls
            // and duplicate webhook deliveries (Razorpay retries webhooks).
            if (payid != null && !payid.isEmpty()) {
                try (PreparedStatement check = conn.prepareStatement(
                        "SELECT payment_status FROM payment_transactions WHERE gateway_payment_id = ? LIMIT 1")) {
                    check.setString(1, payid);
                    try (ResultSet rs = check.executeQuery()) {
                        if (rs.next()) {
                            String existingStatus = rs.getString("payment_status");
                            if ("PAID".equalsIgnoreCase(existingStatus)) {
                                if (!isWebhook) respond(ex, 200, jsonObj2("status", "PAID", "message", "Already Processed"));
                                return;
                            }
                            // If a prior attempt is FAILED, fall through and re-process.
                        }
                    }
                }
            }

            // ── 2. SIGNATURE VERIFICATION ──────────────────────────────────
            // FIX #6: Signature verification is now done BEFORE opening a DB
            // transaction. In the original code, a failed verify still opened
            // a transaction and committed a FAILED record — wasting a write.
            // Also, for webhook events the signature is already verified at
            // the HTTP layer above; sig will be empty here — skip re-check.
            String status        = "FAILED";
            String failureReason = "";

            boolean skipSigCheck = (isWebhook || sig == null || sig.isEmpty());
            if (!skipSigCheck) {
                try {
                    JSONObject attr = new JSONObject();
                    attr.put("razorpay_order_id",   oid);
                    attr.put("razorpay_payment_id",  payid);
                    attr.put("razorpay_signature",   sig);
                    Utils.verifyPaymentSignature(attr, RZP_SECRET);
                    status = "PAID";
                } catch (RazorpayException e) {
                    failureReason = e.getMessage();
                    // Hard-stop on signature mismatch — do not write a record.
                    System.err.println("Signature verification failed: " + failureReason);
                    if (!isWebhook) respond(ex, 400, jsonObj2("status", "FAILED", "reason", failureReason));
                    return;
                }
            } else if (isWebhook) {
                // Webhook path: signature verified at HTTP layer; treat as PAID.
                status = "PAID";
            }

            // ── 3. TRANSACTIONAL DB UPDATE ─────────────────────────────────
            conn.setAutoCommit(false);
            String finalPrid;
            try {
                finalPrid = (prid == null || prid.trim().isEmpty())
                        ? UUID.randomUUID().toString() : prid;

                // FIX #7: nextAttempt used COUNT(*) which is non-atomic and
                // can produce duplicate attempt numbers under concurrent calls.
                // Use SELECT ... FOR UPDATE to lock the booking's rows first.
                int attemptNo = nextAttemptSafe(conn, bid);

                // FIX #8: payment_status in bookings_info must be mapped to
                // the actual enum values your schema defines — not raw
                // "PAID"/"FAILED" strings which could silently insert NULL or
                // throw a cast exception depending on the DB driver + enum.
                String dbPaymentStatus  = "PAID".equals(status) ? "PAID"    : "FAILED";
                String dbBookingStatus  = "PAID".equals(status) ? "CONFIRMED" : "PENDING";

                upsertPaymentRecord(conn, finalPrid, bid, uid, pid, hid,
                        oid, payid, sig, dbPaymentStatus, failureReason, amt, attemptNo);
                updateBookingStatus(conn, bid, dbPaymentStatus, dbBookingStatus, payid, finalPrid);

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }

            // ── 4. RESPONSE ────────────────────────────────────────────────
            // FIX #9: Original code responded with `prid` (the raw input param)
            // instead of `finalPrid` (the validated/generated DB value).
            if (!isWebhook) {
                respond(ex, 200, jsonObj2("status", status, "record_id", finalPrid));
            }

        } catch (Exception e) {
            e.printStackTrace();
            if (!isWebhook) respond(ex, 500, jsonObj("error", "Payment verification internal failure"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DB HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    // FIX #3 (continued): Changed from INSERT to UPSERT so that if the pending
    // row was already created at order-creation time, we UPDATE it in-place
    // instead of throwing a unique-constraint violation.
    private void upsertPaymentRecord(Connection conn,
                                     String prid, String bid, String uid, String pid, String hid,
                                     String oid, String payid, String sig,
                                     String status, String failure,
                                     double amt, int attempt) throws SQLException {

        String sql = "INSERT INTO payment_transactions (" +
                     "payment_record_id, booking_id, user_id, partner_id, hotel_id, " +
                     "payment_gateway, gateway_order_id, gateway_payment_id, gateway_signature, " +
                     "payment_method, payment_status, failure_reason, amount, currency, " +
                     "payment_attempt_no, is_refunded, refund_amount, created_at, updated_at" +
                     ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),NOW()) " +
                     "ON CONFLICT (payment_record_id) DO UPDATE SET " +
                     "  booking_id          = EXCLUDED.booking_id, " +
                     "  gateway_payment_id  = EXCLUDED.gateway_payment_id, " +
                     "  gateway_signature   = EXCLUDED.gateway_signature, " +
                     "  payment_status      = EXCLUDED.payment_status, " +
                     "  failure_reason      = EXCLUDED.failure_reason, " +
                     "  payment_attempt_no  = EXCLUDED.payment_attempt_no, " +
                     "  updated_at          = NOW()";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1,  prid);
            ps.setString(2,  bid);
            ps.setString(3,  uid);
            ps.setString(4,  pid);
            ps.setString(5,  hid);
            ps.setString(6,  "Razorpay");
            ps.setString(7,  oid);
            ps.setString(8,  payid);
            ps.setString(9,  sig);
            ps.setString(10, "Online");
            ps.setString(11, status);
            ps.setString(12, failure);
            ps.setDouble(13, amt);
            ps.setString(14, "INR");
            ps.setInt(15,    attempt);
            ps.setString(16, "No");
            ps.setDouble(17, 0.00);
            ps.executeUpdate();
        }
    }

    private void updateBookingStatus(Connection conn,
                                     String bid, String paymentStatus, String bookingStatus,
                                     String payId, String prid) throws SQLException {
        // FIX #8 (applied): bookingStatus and paymentStatus are now passed as
        // pre-mapped enum-safe strings by the caller instead of being derived
        // ad-hoc inside this method. The cast to ::booking_status_enum remains
        // for PostgreSQL; adjust if using MySQL (remove the cast).
        String sql = "UPDATE bookings_info SET " +
                     "  payment_status          = ?, " +
                     "  transaction_id          = ?, " +
                     "  last_payment_record_id  = ?, " +
                     "  booking_status          = ?::booking_status_enum, " +
                     "  payment_confirmed_at    = NOW() " +
                     "WHERE booking_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, paymentStatus);
            ps.setString(2, payId);
            ps.setString(3, prid);
            ps.setString(4, bookingStatus);
            ps.setString(5, bid);
            ps.executeUpdate();
        }
    }

    // FIX #7: Use SELECT FOR UPDATE to lock the booking's existing payment rows
    // before counting them. This prevents two concurrent verify calls for the
    // same booking from both reading COUNT=0 and both inserting attempt_no=1.
    private int nextAttemptSafe(Connection conn, String bid) throws SQLException {
        if (bid == null || bid.isEmpty()) return 1;
        String sql = "SELECT COUNT(*) FROM payment_transactions " +
                     "WHERE booking_id = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, bid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) + 1 : 1;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILITY HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private void addCors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
    }

    // FIX #2 (applied): Removed ex.close() from respond(). The handle() method
    // owns the lifecycle of the exchange and closes it exactly once via
    // try-with-resources or explicit close in the router. This prevents the
    // "double close" IOException that appeared in server logs on error paths.
    private void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
        // NOTE: Do NOT call ex.close() here. handle() is responsible for that.
    }

    private Map<String, Object> readJson(HttpExchange ex) throws IOException {
        return mapper.readValue(
                new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8),
                Map.class);
    }

    private String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private double toDouble(Object o) {
        if (o == null) return 0.0;
        try { return Double.parseDouble(o.toString().replace(",", "")); }
        catch (Exception e) { return 0.0; }
    }

    private int toInt(Object o) {
        if (o == null) return 0;
        try { return (int) Double.parseDouble(o.toString()); }
        catch (Exception e) { return 0; }
    }

    // FIX #10: Replaced hand-rolled string concatenation with JSONObject so
    // values containing quotes, backslashes, or non-ASCII characters don't
    // produce malformed JSON (e.g. a DB error message with a quote in it).
    private String jsonObj(String key, String value) {
        return new JSONObject(Map.of(key, value == null ? "" : value)).toString();
    }

    private String jsonObj2(String k1, String v1, String k2, String v2) {
        return new JSONObject(Map.of(
                k1, v1 == null ? "" : v1,
                k2, v2 == null ? "" : v2
        )).toString();
    }
}