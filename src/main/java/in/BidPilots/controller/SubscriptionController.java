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

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final SubscriptionUserLoginService subscriptionUserLoginService;
    private final UserRegistrationRepository userRepository;
    private final AuthenticationManager authenticationManager;

    // ─────────────────────────────────────────────────────────────
    // SUBSCRIPTION PAGE LOGIN - NO SUBSCRIPTION CHECK
    // This endpoint allows users with expired subscriptions to log in
    // and access the subscription page to renew
    // ─────────────────────────────────────────────────────────────
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> subscriptionLogin(
            @Valid @RequestBody UserLoginDTO loginDTO,
            HttpServletRequest request) {
        
        log.info("🔐 Subscription page login request for email: {}", loginDTO.getEmail());

        try {
            // Authenticate with Spring Security
            Authentication authentication = authenticationManager
                    .authenticate(new UsernamePasswordAuthenticationToken(loginDTO.getEmail(), loginDTO.getPassword()));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            HttpSession session = request.getSession(true);
            session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

            // Use the special subscription login service (no subscription expiry check)
            Map<String, Object> serviceResponse = subscriptionUserLoginService.loginForSubscription(loginDTO);

            if (serviceResponse.containsKey("success") && (Boolean) serviceResponse.get("success")) {
                // Add session info to response
                serviceResponse.put("sessionId", session.getId());
                log.info("✅ Subscription page login successful for: {}", loginDTO.getEmail());
                return ResponseEntity.ok().header("Content-Type", "application/json").body(serviceResponse);
            } else {
                return ResponseEntity.badRequest().header("Content-Type", "application/json").body(serviceResponse);
            }

        } catch (BadCredentialsException e) {
            log.error("Bad credentials for subscription login: {}", loginDTO.getEmail());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Invalid email or password"));
        } catch (DisabledException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Account is disabled"));
        } catch (LockedException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Account is locked"));
        } catch (Exception e) {
            log.error("Subscription login failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Invalid email or password"));
        }
    }

    // GET /api/subscriptions/plans
    // Public — no auth required. Used by pricing page.
    @GetMapping("/plans")
    public ResponseEntity<Map<String, Object>> getPlans() {
        return ResponseEntity.ok(subscriptionService.getAllPlans());
    }

    // GET /api/subscriptions/my-subscription
    // Returns current subscription status, days remaining, etc.
    @GetMapping("/my-subscription")
    public ResponseEntity<Map<String, Object>> getMySubscription() {
        Long userId = resolveUserId();
        if (userId == null) return unauthorized();

        Map<String, Object> result = subscriptionService.getMySubscription(userId);
        boolean ok = result.containsKey("success") && (Boolean) result.get("success");
        return ok ? ResponseEntity.ok(result) : ResponseEntity.status(404).body(result);
    }

    // POST /api/subscriptions/create-order
    // Body: { "planDuration": "MONTHLY" }
    // Step 1 of payment: creates Razorpay order, returns orderId to frontend.
    @PostMapping("/create-order")
    public ResponseEntity<Map<String, Object>> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        Long userId = resolveUserId();
        if (userId == null) return unauthorized();

        log.info("💳 Create order userId={} plan={}", userId, request.getPlanDuration());
        Map<String, Object> result = subscriptionService.createOrder(userId, request);
        boolean ok = result.containsKey("success") && (Boolean) result.get("success");
        return ok ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    // POST /api/subscriptions/verify-payment
    // Body: { razorpayOrderId, razorpayPaymentId, razorpaySignature }
    // Step 2: frontend sends these after Razorpay modal succeeds.
    // Verifies HMAC signature → activates subscription.
    @PostMapping("/verify-payment")
    public ResponseEntity<Map<String, Object>> verifyPayment(@Valid @RequestBody VerifyPaymentRequest request) {
        Long userId = resolveUserId();
        if (userId == null) return unauthorized();

        log.info("🔐 Verify payment userId={} orderId={}", userId, request.getRazorpayOrderId());
        Map<String, Object> result = subscriptionService.verifyPayment(userId, request);
        boolean ok = result.containsKey("success") && (Boolean) result.get("success");
        return ok ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    // POST /api/subscriptions/cancel
    // Access continues until end date.
    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancel() {
        Long userId = resolveUserId();
        if (userId == null) return unauthorized();

        log.info("🚫 Cancel subscription userId={}", userId);
        Map<String, Object> result = subscriptionService.cancelSubscription(userId);
        boolean ok = result.containsKey("success") && (Boolean) result.get("success");
        return ok ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    // ── Private helpers ──

    private Long resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
            return null;
        }
        try {
            User user = userRepository.findByEmail(auth.getName())
                    .orElseThrow(() -> new RuntimeException("User not found"));
            return user.getId();
        } catch (Exception e) {
            log.error("Cannot resolve userId: {}", e.getMessage());
            return null;
        }
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401)
                .body(Map.of("success", false, "message", "Not authenticated"));
    }
}