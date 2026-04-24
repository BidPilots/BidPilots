package in.BidPilots.service;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import in.BidPilots.dto.subscription.CreateOrderRequest;
import in.BidPilots.dto.subscription.SubscriptionResponseDTO;
import in.BidPilots.dto.subscription.VerifyPaymentRequest;
import in.BidPilots.entity.Subscription;
import in.BidPilots.enums.PlanDuration;
import in.BidPilots.enums.SubscriptionStatus;
import in.BidPilots.repository.SubscriptionRepository;
import in.BidPilots.util.RazorpaySignatureVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Core subscription service.
 *
 * Key behaviours:
 *   - createOrder: creates a Razorpay order and tags it on the subscription row
 *   - verifyPayment: verifies HMAC, then EXTENDS if already active or starts fresh
 *   - handleWebhook: server-side backup in case the client verify-payment call fails
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private final SubscriptionRepository   subscriptionRepository;
    private final RazorpaySignatureVerifier signatureVerifier;
    private final RazorpayClient           razorpayClient;   // provided by RazorpayConfig

    @Value("${razorpay.key.id}")
    private String razorpayKeyId;

    @Value("${app.base.url:http://localhost:8080}")
    private String appBaseUrl;

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC PLANS  (no auth)
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> getAllPlans() {
        List<Map<String, Object>> plans = new ArrayList<>();
        for (PlanDuration plan : PlanDuration.values()) {
            if (plan == PlanDuration.TRIAL) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("planDuration",  plan.name());
            p.put("displayName",   plan.getDisplayName());
            p.put("priceRupees",   plan.getPriceRupees());
            p.put("durationDays",  plan.getDurationDays());
            p.put("pricePerDay",   plan.getPricePerDay());
            p.put("isFree",        plan.isFree());
            plans.add(p);
        }
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("plans", plans);
        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET MY SUBSCRIPTION
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> getMySubscription(Long userId) {
        Optional<Subscription> opt = subscriptionRepository.findByUserId(userId);
        if (opt.isEmpty()) {
            return Map.of("success", false, "message", "No subscription found");
        }
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("subscription", SubscriptionResponseDTO.from(opt.get()));
        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 1 — CREATE RAZORPAY ORDER
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> createOrder(Long userId, CreateOrderRequest request) {
        PlanDuration plan = request.getPlanDuration();

        if (plan == PlanDuration.TRIAL) {
            return Map.of("success", false, "message", "Trial plan cannot be purchased");
        }

        try {
            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount",   plan.getAmountInPaise());
            orderRequest.put("currency", "INR");
            orderRequest.put("receipt",  "sub_" + userId + "_" + System.currentTimeMillis());

            JSONObject notes = new JSONObject();
            notes.put("userId",       userId.toString());
            notes.put("planDuration", plan.name());
            orderRequest.put("notes", notes);

            Order order = razorpayClient.orders.create(orderRequest);
            String razorpayOrderId = order.get("id");

            // Tag pending order on existing subscription row, or create a PENDING holder
            Subscription sub = subscriptionRepository.findByUserId(userId).orElse(null);
            if (sub != null) {
                sub.setRazorpayOrderId(razorpayOrderId);
                sub.setPendingPlanDuration(plan);
                subscriptionRepository.save(sub);
            } else {
                Subscription pending = new Subscription();
                pending.setUserId(userId);
                pending.setPlanDuration(plan);
                pending.setStatus(SubscriptionStatus.PENDING);
                pending.setStartDate(LocalDateTime.now());
                pending.setEndDate(LocalDateTime.now());
                pending.setRazorpayOrderId(razorpayOrderId);
                pending.setPendingPlanDuration(plan);
                pending.setAmountPaidPaise(0);
                subscriptionRepository.save(pending);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success",         true);
            response.put("orderId",          razorpayOrderId);
            response.put("amount",           plan.getAmountInPaise());
            response.put("currency",         "INR");
            response.put("planDuration",     plan.name());
            response.put("planDisplayName",  plan.getDisplayName());
            // FIX: frontend Razorpay options expects the key as "keyId"
            response.put("keyId",            razorpayKeyId);
            response.put("callbackUrl",      appBaseUrl + "/payment/callback");
            return response;

        } catch (Exception e) {
            log.error("❌ Failed to create Razorpay order userId={}: {}", userId, e.getMessage(), e);
            return Map.of("success", false,
                    "message", "Failed to create payment order: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 2 — VERIFY PAYMENT & ACTIVATE / EXTEND SUBSCRIPTION
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> verifyPayment(Long userId, VerifyPaymentRequest request) {
        String orderId   = request.getRazorpayOrderId();
        String paymentId = request.getRazorpayPaymentId();
        String signature = request.getRazorpaySignature();

        log.info("🔐 verifyPayment — userId={} orderId={} paymentId={}", userId, orderId, paymentId);

        // 1. Verify HMAC signature
        boolean valid = signatureVerifier.verifyPaymentSignature(orderId, paymentId, signature);
        if (!valid) {
            log.warn("❌ Signature mismatch — userId={} orderId={}", userId, orderId);
            return Map.of("success", false,
                    "message", "Payment verification failed — invalid signature");
        }

        // 2. Find subscription by orderId, fall back to userId
        Subscription sub = subscriptionRepository.findByRazorpayOrderId(orderId)
                .or(() -> subscriptionRepository.findByUserId(userId))
                .orElse(null);

        if (sub == null) {
            log.error("❌ No subscription record for orderId={} userId={}", orderId, userId);
            return Map.of("success", false,
                    "message", "Subscription record not found. Please contact support.");
        }

        // 3. Idempotency guard — already activated by a previous call or webhook
        if (SubscriptionStatus.ACTIVE.equals(sub.getStatus())
                && paymentId.equals(sub.getRazorpayPaymentId())) {
            log.info("ℹ️ Payment already verified — paymentId={}", paymentId);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success",      true);
            resp.put("message",      "Subscription already active. Valid until: " + sub.getFormattedEndDate());
            resp.put("subscription", SubscriptionResponseDTO.from(sub));
            resp.put("extended",     false);
            return resp;
        }

        // 4. Determine the plan being purchased
        PlanDuration plan = sub.getPendingPlanDuration() != null
                ? sub.getPendingPlanDuration()
                : sub.getPlanDuration();

        if (plan == null || plan == PlanDuration.TRIAL) {
            log.error("❌ Invalid plan on subscription id={}", sub.getId());
            return Map.of("success", false, "message", "Invalid plan — please contact support");
        }

        // 5. Calculate new end date — extension or fresh start
        LocalDateTime now              = LocalDateTime.now();
        boolean       isCurrentlyActive = sub.isCurrentlyActive();
        LocalDateTime newEndDate       = isCurrentlyActive
                ? sub.getEndDate().plusDays(plan.getDurationDays())
                : now.plusDays(plan.getDurationDays());

        if (isCurrentlyActive) {
            log.info("📅 Extending subscription id={} from {} → {}",
                    sub.getId(), sub.getEndDate(), newEndDate);
        } else {
            log.info("📅 Fresh subscription start id={} endDate={}", sub.getId(), newEndDate);
        }

        // 6. Persist
        sub.setPlanDuration(plan);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setStartDate(isCurrentlyActive ? sub.getStartDate() : now);
        sub.setEndDate(newEndDate);
        sub.setRazorpayOrderId(orderId);
        sub.setRazorpayPaymentId(paymentId);
        sub.setRazorpaySignature(signature);
        sub.setAmountPaidPaise(plan.getPricePaise());
        sub.setLastPaymentDate(now);
        sub.setPendingPlanDuration(null);

        subscriptionRepository.save(sub);

        log.info("✅ Subscription activated/extended — userId={} plan={} endDate={} extended={}",
                userId, plan.getDisplayName(), newEndDate, isCurrentlyActive);

        Map<String, Object> response = new HashMap<>();
        response.put("success",      true);
        response.put("message",      isCurrentlyActive
                ? "Subscription extended! New expiry: " + sub.getFormattedEndDate()
                : "Subscription activated! Valid until: " + sub.getFormattedEndDate());
        response.put("subscription", SubscriptionResponseDTO.from(sub));
        response.put("extended",     isCurrentlyActive);
        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CANCEL
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> cancelSubscription(Long userId) {
        Optional<Subscription> opt = subscriptionRepository.findByUserId(userId);
        if (opt.isEmpty()) {
            return Map.of("success", false, "message", "No subscription found");
        }
        Subscription sub = opt.get();
        if (sub.getStatus() == SubscriptionStatus.EXPIRED) {
            return Map.of("success", false, "message", "Subscription is already expired");
        }
        if (sub.getStatus() == SubscriptionStatus.CANCELLED) {
            return Map.of("success", false, "message", "Subscription is already cancelled");
        }
        sub.setStatus(SubscriptionStatus.CANCELLED);
        subscriptionRepository.save(sub);
        log.info("🚫 Subscription cancelled — userId={} accessUntil={}", userId, sub.getFormattedEndDate());
        Map<String, Object> response = new HashMap<>();
        response.put("success",      true);
        response.put("message",      "Subscription cancelled. Access continues until " + sub.getFormattedEndDate());
        response.put("subscription", SubscriptionResponseDTO.from(sub));
        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE TRIAL (called from UserRegistrationService)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void createTrialSubscription(Long userId) {
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
        trial.setEndDate(now.plusDays(PlanDuration.TRIAL.getDurationDays()));
        trial.setAmountPaidPaise(0);
        subscriptionRepository.save(trial);
        log.info("🎁 Trial created userId={} expires={}", userId, trial.getEndDate());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ACTIVE CHECK (used by interceptors / filter guards)
    // ─────────────────────────────────────────────────────────────────────────

    public boolean hasActiveSubscription(Long userId) {
        return subscriptionRepository.findByUserId(userId)
                .map(Subscription::isCurrentlyActive)
                .orElse(false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RAZORPAY WEBHOOK HANDLER (server-side backup)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> handleWebhook(String payload, String webhookSignature) {
        boolean valid = signatureVerifier.verifyWebhookSignature(
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), webhookSignature);

        if (!valid) {
            log.warn("❌ Webhook signature invalid");
            return Map.of("success", false, "message", "Invalid webhook signature");
        }

        try {
            JSONObject event     = new JSONObject(payload);
            String     eventType = event.optString("event");
            log.info("📥 Razorpay webhook event: {}", eventType);

            if ("payment.captured".equals(eventType)) {
                JSONObject paymentEntity = event
                        .getJSONObject("payload")
                        .getJSONObject("payment")
                        .getJSONObject("entity");

                String orderId   = paymentEntity.optString("order_id");
                String paymentId = paymentEntity.optString("id");

                log.info("💰 Webhook payment.captured — orderId={} paymentId={}", orderId, paymentId);

                subscriptionRepository.findByRazorpayOrderId(orderId).ifPresent(sub -> {
                    // Skip if client-side verify-payment already handled this
                    if (SubscriptionStatus.ACTIVE.equals(sub.getStatus())
                            && paymentId.equals(sub.getRazorpayPaymentId())) {
                        log.info("ℹ️ Webhook: already activated client-side — orderId={}", orderId);
                        return;
                    }

                    PlanDuration plan = sub.getPendingPlanDuration() != null
                            ? sub.getPendingPlanDuration()
                            : sub.getPlanDuration();

                    if (plan == null || plan == PlanDuration.TRIAL) {
                        log.error("❌ Webhook: invalid plan on subscription id={}", sub.getId());
                        return;
                    }

                    LocalDateTime now        = LocalDateTime.now();
                    boolean       isActive   = sub.isCurrentlyActive();
                    LocalDateTime newEndDate = isActive
                            ? sub.getEndDate().plusDays(plan.getDurationDays())
                            : now.plusDays(plan.getDurationDays());

                    sub.setPlanDuration(plan);
                    sub.setStatus(SubscriptionStatus.ACTIVE);
                    sub.setStartDate(isActive ? sub.getStartDate() : now);
                    sub.setEndDate(newEndDate);
                    sub.setRazorpayPaymentId(paymentId);
                    sub.setAmountPaidPaise(plan.getPricePaise());
                    sub.setLastPaymentDate(now);
                    sub.setPendingPlanDuration(null);
                    subscriptionRepository.save(sub);

                    log.info("✅ Webhook: subscription activated userId={} endDate={}",
                            sub.getUserId(), newEndDate);
                });
            }
            return Map.of("success", true);

        } catch (Exception e) {
            log.error("❌ Webhook processing error: {}", e.getMessage(), e);
            return Map.of("success", false, "message", "Webhook processing error");
        }
    }
}