package in.BidPilots.controller;

import in.BidPilots.dto.subscription.CreateOrderRequest;
import in.BidPilots.dto.subscription.VerifyPaymentRequest;
import in.BidPilots.dto.UserLogin.UserLoginDTO;
import in.BidPilots.entity.User;
import in.BidPilots.repository.UserRegistrationRepository;
import in.BidPilots.service.SubscriptionService;
import in.BidPilots.service.SubscriptionUserLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
@Slf4j
public class SubscriptionController {

    private final SubscriptionService            subscriptionService;
    private final SubscriptionUserLoginService   subscriptionUserLoginService;
    private final UserRegistrationRepository     userRepository;
    private final AuthenticationManager          authenticationManager;

    // ─────────────────────────────────────────────────────────────────────────
    // SUBSCRIPTION PAGE LOGIN — allows expired-subscription users to log in
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> subscriptionLogin(
            @Valid @RequestBody UserLoginDTO loginDTO,
            HttpServletRequest request) {

        log.info("🔐 Subscription login — email={}", loginDTO.getEmail());

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginDTO.getEmail(), loginDTO.getPassword()));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            HttpSession session = request.getSession(true);
            session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

            Map<String, Object> serviceResponse =
                    subscriptionUserLoginService.loginForSubscription(loginDTO);

            if (Boolean.TRUE.equals(serviceResponse.get("success"))) {
                serviceResponse.put("sessionId", session.getId());
                log.info("✅ Subscription login OK — email={}", loginDTO.getEmail());
                return ResponseEntity.ok(serviceResponse);
            }
            return ResponseEntity.badRequest().body(serviceResponse);

        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Invalid email or password"));
        } catch (DisabledException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Account is disabled"));
        } catch (LockedException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Account is locked"));
        } catch (Exception e) {
            log.error("Subscription login error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Login failed — please try again"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PLANS — public, no auth
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/plans")
    public ResponseEntity<Map<String, Object>> getPlans() {
        return ResponseEntity.ok(subscriptionService.getAllPlans());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MY SUBSCRIPTION
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/my-subscription")
    public ResponseEntity<Map<String, Object>> getMySubscription() {
        Long userId = resolveUserId();
        if (userId == null) return unauthorized();

        Map<String, Object> result = subscriptionService.getMySubscription(userId);
        return Boolean.TRUE.equals(result.get("success"))
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 1 — Create Razorpay order
    // Body: { "planDuration": "MONTHLY" }
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/create-order")
    public ResponseEntity<Map<String, Object>> createOrder(
            @Valid @RequestBody CreateOrderRequest request) {

        Long userId = resolveUserId();
        if (userId == null) return unauthorized();

        log.info("💳 Create order — userId={} plan={}", userId, request.getPlanDuration());
        Map<String, Object> result = subscriptionService.createOrder(userId, request);
        return Boolean.TRUE.equals(result.get("success"))
                ? ResponseEntity.ok(result)
                : ResponseEntity.badRequest().body(result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 2 — Verify payment & activate / extend subscription
    // Body: { razorpayOrderId, razorpayPaymentId, razorpaySignature }
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/verify-payment")
    public ResponseEntity<Map<String, Object>> verifyPayment(
            @Valid @RequestBody VerifyPaymentRequest request) {

        Long userId = resolveUserId();
        if (userId == null) return unauthorized();

        log.info("🔐 Verify payment — userId={} orderId={}", userId, request.getRazorpayOrderId());
        Map<String, Object> result = subscriptionService.verifyPayment(userId, request);
        return Boolean.TRUE.equals(result.get("success"))
                ? ResponseEntity.ok(result)
                : ResponseEntity.badRequest().body(result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CANCEL — access continues until endDate
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancel() {
        Long userId = resolveUserId();
        if (userId == null) return unauthorized();

        log.info("🚫 Cancel subscription — userId={}", userId);
        Map<String, Object> result = subscriptionService.cancelSubscription(userId);
        return Boolean.TRUE.equals(result.get("success"))
                ? ResponseEntity.ok(result)
                : ResponseEntity.badRequest().body(result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RAZORPAY WEBHOOK — must be PUBLIC in SecurityConfig
    // Always return 200 to prevent Razorpay retries; log errors internally.
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {

        log.info("📥 Razorpay webhook received, signature present={}", signature != null);

        if (signature == null || signature.isBlank()) {
            log.warn("❌ Webhook without signature — rejecting");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Missing webhook signature"));
        }

        Map<String, Object> result = subscriptionService.handleWebhook(payload, signature);
        // Always return 200 to Razorpay
        return ResponseEntity.ok(result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Long resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        try {
            Optional<User> userOpt = userRepository.findByEmail(auth.getName());
            return userOpt.map(User::getId).orElse(null);
        } catch (Exception e) {
            log.error("Cannot resolve userId for email={}: {}", auth.getName(), e.getMessage());
            return null;
        }
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("success", false, "message", "Not authenticated — please log in"));
    }
}