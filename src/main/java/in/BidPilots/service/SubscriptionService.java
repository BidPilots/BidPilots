package in.BidPilots.service;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import in.BidPilots.dto.subscription.CreateOrderRequest;
import in.BidPilots.dto.subscription.SubscriptionResponseDTO;
import in.BidPilots.dto.subscription.VerifyPaymentRequest;
import in.BidPilots.entity.Subscription;
import in.BidPilots.enums.PlanDuration;
import in.BidPilots.enums.SubscriptionStatus;
import in.BidPilots.exception.SubscriptionExpiredException;
import in.BidPilots.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;

    @Value("${razorpay.key.id}")
    private String razorpayKeyId;

    @Value("${razorpay.key.secret}")
    private String razorpayKeySecret;

    // ─────────────────────────────────────────────────────────────────
    // PENDING ORDERS STORE
    //
    // KEY FIX: We no longer write to the subscriptions table when the user
    // selects a plan and clicks Pay. We only write to DB inside verifyPayment()
    // AFTER the Razorpay HMAC signature is confirmed.
    //
    // This map holds { razorpayOrderId → PendingOrder } in memory.
    // It is cleared automatically when payment succeeds or the JVM restarts.
    // Entries expire after 30 minutes to avoid memory leaks.
    // ─────────────────────────────────────────────────────────────────

    private static final Map<String, PendingOrder> pendingOrders = new ConcurrentHashMap<>();

    /** Lightweight in-memory record — never persisted until payment verified */
    private record PendingOrder(
            Long         userId,
            PlanDuration plan,
            LocalDateTime createdAt
    ) {
        boolean isExpired() {
            return createdAt.plusMinutes(30).isBefore(LocalDateTime.now());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 1. TRIAL ACTIVATION  — called from UserRegistrationService
    // ─────────────────────────────────────────────────────────────────

    @Transactional
    public void activateFreeTrial(Long userId) {
        if (subscriptionRepository.existsByUserId(userId)) {
            log.warn("⚠️ Trial already exists for userId={}", userId);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        Subscription trial = new Subscription();
        trial.setUserId(userId);
        trial.setPlanDuration(PlanDuration.TRIAL);
        trial.setStatus(SubscriptionStatus.TRIAL);
        trial.setStartDate(now);
        trial.setEndDate(now.plusDays(30));
        trial.setAmountPaidPaise(0);
        subscriptionRepository.save(trial);
        log.info("✅ Trial activated userId={} expires={}", userId, trial.getEndDate());
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. CREATE RAZORPAY ORDER
    //    POST /api/subscriptions/create-order
    //
    // FIX: This method ONLY creates a Razorpay order and stores the
    // pending intent in memory.  It does NOT touch the subscriptions table
    // at all.  The DB is updated only inside verifyPayment() below.
    // ─────────────────────────────────────────────────────────────────

    public Map<String, Object> createOrder(Long userId, CreateOrderRequest request) {
        Map<String, Object> response = new HashMap<>();
        PlanDuration plan = request.getPlanDuration();

        if (plan.isFree()) {
            response.put("success", false);
            response.put("message", "Trial activates automatically on registration.");
            return response;
        }

        // Clean up stale pending orders while we're here (simple housekeeping)
        pendingOrders.entrySet().removeIf(e -> e.getValue().isExpired());

        try {
            RazorpayClient client = new RazorpayClient(razorpayKeyId, razorpayKeySecret);

            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount",          plan.getPricePaise());
            orderRequest.put("currency",        "INR");
            orderRequest.put("receipt",         "bp_" + userId + "_" + System.currentTimeMillis());
            orderRequest.put("payment_capture", 1);

            JSONObject notes = new JSONObject();
            notes.put("userId",      userId.toString());
            notes.put("plan",        plan.name());
            notes.put("durationDays", plan.getDurationDays());
            orderRequest.put("notes", notes);

            Order  order   = client.orders.create(orderRequest);
            String orderId = order.get("id");

            // ── Store intent in memory ONLY — DO NOT write to DB ──
            pendingOrders.put(orderId, new PendingOrder(userId, plan, LocalDateTime.now()));

            log.info("📦 Order created (not yet paid): orderId={} userId={} plan={} ₹{}",
                    orderId, userId, plan.name(), plan.getPriceRupees());

            response.put("success",      true);
            response.put("orderId",      orderId);
            response.put("amount",       plan.getPricePaise());
            response.put("currency",     "INR");
            response.put("keyId",        razorpayKeyId);
            response.put("planName",     plan.getDisplayName());
            response.put("amountRupees", plan.getPriceRupees());

        } catch (RazorpayException e) {
            log.error("❌ Razorpay order failed userId={}: {}", userId, e.getMessage());
            response.put("success", false);
            response.put("message", "Payment gateway error. Please try again.");
        }

        return response;
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. VERIFY PAYMENT  — the ONLY place that writes to subscriptions table
    //    POST /api/subscriptions/verify-payment
    //
    // Flow:
    //   a) Verify Razorpay HMAC signature — if wrong, reject immediately
    //   b) Look up the pending order in memory — get userId + plan
    //   c) Only now update the subscriptions table
    //   d) Remove the pending order from memory
    // ─────────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> verifyPayment(Long userId, VerifyPaymentRequest req) {
        Map<String, Object> response = new HashMap<>();

        try {
            // Step 1 — Verify HMAC-SHA256 signature (Razorpay standard)
            String payload  = req.getRazorpayOrderId() + "|" + req.getRazorpayPaymentId();
            String computed = hmacSha256(payload, razorpayKeySecret);

            if (!computed.equals(req.getRazorpaySignature())) {
                log.error("❌ Signature mismatch userId={} orderId={}", userId, req.getRazorpayOrderId());
                response.put("success", false);
                response.put("message", "Payment verification failed. Contact support if amount was deducted.");
                return response;
            }

            // Step 2 — Look up the pending order in memory
            PendingOrder pending = pendingOrders.get(req.getRazorpayOrderId());

            if (pending == null) {
                log.error("❌ No pending order found for orderId={}", req.getRazorpayOrderId());
                response.put("success", false);
                response.put("message", "Order not found or expired. Please contact support.");
                return response;
            }

            if (pending.isExpired()) {
                pendingOrders.remove(req.getRazorpayOrderId());
                response.put("success", false);
                response.put("message", "Payment session expired. Please try again.");
                return response;
            }

            // Security: confirm the pending order belongs to the requesting user
            if (!pending.userId().equals(userId)) {
                log.error("❌ userId mismatch: pending={} requesting={}", pending.userId(), userId);
                response.put("success", false);
                response.put("message", "Order does not belong to your account.");
                return response;
            }

            // Step 3 — Payment confirmed. NOW update the subscriptions table.
            PlanDuration  plan = pending.plan();
            LocalDateTime now  = LocalDateTime.now();

            Subscription sub = subscriptionRepository.findByUserId(userId)
                    .orElse(new Subscription());   // first paid plan after trial ends

            sub.setUserId(userId);
            sub.setPlanDuration(plan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setStartDate(now);
            sub.setEndDate(now.plusDays(plan.getDurationDays()));
            sub.setRazorpayOrderId(req.getRazorpayOrderId());
            sub.setRazorpayPaymentId(req.getRazorpayPaymentId());
            sub.setRazorpaySignature(req.getRazorpaySignature());
            sub.setAmountPaidPaise(plan.getPricePaise());
            sub.setLastPaymentDate(now);

            subscriptionRepository.save(sub);

            // Step 4 — Remove pending order from memory (clean up)
            pendingOrders.remove(req.getRazorpayOrderId());

            log.info("✅ Payment verified + DB updated: userId={} plan={} valid until {}",
                    userId, plan.name(), sub.getEndDate());

            response.put("success",       true);
            response.put("message",       "Payment successful! Your " + plan.getDisplayName() + " plan is now active.");
            response.put("subscription",  SubscriptionResponseDTO.from(sub));
            response.put("endDate",       sub.getEndDate().toString());
            response.put("daysRemaining", sub.daysRemaining());

        } catch (Exception e) {
            log.error("❌ verifyPayment error userId={}: {}", userId, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Verification error: " + e.getMessage());
        }

        return response;
    }

    // ─────────────────────────────────────────────────────────────────
    // 4. GET CURRENT SUBSCRIPTION
    // ─────────────────────────────────────────────────────────────────

    public Map<String, Object> getMySubscription(Long userId) {
        Map<String, Object> response = new HashMap<>();
        Optional<Subscription> subOpt = subscriptionRepository.findByUserId(userId);

        if (subOpt.isEmpty()) {
            response.put("success",         false);
            response.put("hasSubscription", false);
            response.put("message",         "No subscription found");
            return response;
        }

        Subscription sub = subOpt.get();
        response.put("success",         true);
        response.put("hasSubscription", true);
        response.put("subscription",    SubscriptionResponseDTO.from(sub));
        response.put("isActive",        sub.isCurrentlyActive());
        response.put("daysRemaining",   sub.daysRemaining());
        response.put("isInTrial",       sub.isInTrialPeriod());
        response.put("status",          sub.getStatus().name());
        return response;
    }

    // ─────────────────────────────────────────────────────────────────
    // 5. CANCEL SUBSCRIPTION
    // ─────────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> cancelSubscription(Long userId) {
        Map<String, Object> response = new HashMap<>();
        Subscription sub = subscriptionRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("No subscription found"));

        sub.setStatus(SubscriptionStatus.CANCELLED);
        subscriptionRepository.save(sub);

        log.info("🚫 CANCELLED userId={}", userId);
        response.put("success",     true);
        response.put("message",     "Subscription cancelled. Access continues until " + sub.getEndDate());
        response.put("accessUntil", sub.getEndDate().toString());
        return response;
    }

    // ─────────────────────────────────────────────────────────────────
    // 6. PLAN LISTING  — GET /api/subscriptions/plans
    // ─────────────────────────────────────────────────────────────────

    public Map<String, Object> getAllPlans() {
        List<Map<String, Object>> plans = new ArrayList<>();
        for (PlanDuration plan : PlanDuration.values()) {
            if (plan == PlanDuration.TRIAL) continue;
            Map<String, Object> p = new HashMap<>();
            p.put("id",           plan.name());
            p.put("displayName",  plan.getDisplayName());
            p.put("priceRupees",  plan.getPriceRupees());
            p.put("durationDays", plan.getDurationDays());
            p.put("pricePerDay",  String.format("%.1f",
                    (double) plan.getPriceRupees() / plan.getDurationDays()));
            plans.add(p);
        }
        return Map.of("success", true, "plans", plans);
    }

    // ─────────────────────────────────────────────────────────────────
    // 7. SUBSCRIPTION GUARDS
    // ─────────────────────────────────────────────────────────────────

    public void requireActiveSubscription(Long userId) {
        Optional<Subscription> subOpt = subscriptionRepository.findByUserId(userId);
        if (subOpt.isEmpty() || !subOpt.get().isCurrentlyActive()) {
            log.warn("🔒 Access blocked — no active subscription: userId={}", userId);
            throw new SubscriptionExpiredException(
                    "Your subscription has expired. Please renew to continue using BidPilots.");
        }
    }

    public boolean hasActiveSubscription(Long userId) {
        return subscriptionRepository.findByUserId(userId)
                .map(Subscription::isCurrentlyActive)
                .orElse(false);
    }

    // ─────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────

    private String hmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
        return Hex.encodeHexString(mac.doFinal(data.getBytes("UTF-8")));
    }
}