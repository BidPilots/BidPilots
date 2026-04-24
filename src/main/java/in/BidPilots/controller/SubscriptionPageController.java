package in.BidPilots.controller;

import in.BidPilots.service.UserRegistration.UserRegistrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Serves HTML pages for the subscription module.
 * Must be @Controller — NOT @RestController.
 *
 * SecurityConfig must permit:
 *   /api/subscriptions/plans   — JSON data (public)
 *   /payment/callback          — Razorpay redirect (public)
 *   /api/subscriptions/webhook — Razorpay webhook  (public)
 *
 * /subscription/plans is authenticated — this controller enforces that
 * server-side and redirects to login if not authenticated.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class SubscriptionPageController {

    // FIX: the original code injected a non-existent "UserLoginService".
    // The correct service that provides getUserByEmail() is UserRegistrationService.
    private final UserRegistrationService userRegistrationService;

    // ─── Subscription Plans HTML Page ─────────────────────────────────────────

    @GetMapping("/subscription/plans")
    public ModelAndView subscriptionPlansPage(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String email,
            @RequestParam(required = false, defaultValue = "false") boolean expired) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAuthenticated = auth != null
                && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());

        if (!isAuthenticated) {
            StringBuilder returnUrl = new StringBuilder("/subscription/plans");
            StringBuilder qs = new StringBuilder();
            if (userId != null) qs.append("&userId=").append(userId);
            if (email  != null) qs.append("&email=").append(email);
            if (expired)        qs.append("&expired=true");
            if (qs.length() > 0) returnUrl.append("?").append(qs.substring(1));

            log.info("🔒 Unauthenticated access to /subscription/plans — redirecting to login");
            return new ModelAndView("redirect:/api/users/login-form?redirect="
                    + URLEncoder.encode(returnUrl.toString(), StandardCharsets.UTF_8));
        }

        ModelAndView mav = new ModelAndView("subscription-plans");

        Map<String, Object> userResponse = userRegistrationService.getUserByEmail(auth.getName());
        if (Boolean.TRUE.equals(userResponse.get("success"))) {
            mav.addObject("user", userResponse.get("user"));
        }

        if (userId != null) mav.addObject("userId",               userId);
        if (email  != null) mav.addObject("userEmail",            email);
        if (expired)        mav.addObject("subscriptionExpired",  true);

        log.info("📄 Subscription plans page — user={}, expired={}", auth.getName(), expired);
        return mav;
    }

    // ─── Payment Callback Page ─────────────────────────────────────────────────

    /**
     * Razorpay redirects the browser here after checkout with:
     *   ?razorpay_payment_id=xxx&razorpay_order_id=xxx&razorpay_signature=xxx
     *
     * The Thymeleaf template (payment-success.html) reads these params via JS
     * and calls POST /api/subscriptions/verify-payment, then redirects to the
     * dashboard on success.
     */
    @GetMapping("/payment/callback")
    public ModelAndView paymentCallback(
            @RequestParam(required = false) String razorpay_payment_id,
            @RequestParam(required = false) String razorpay_order_id,
            @RequestParam(required = false) String razorpay_signature) {

        log.info("💳 Payment callback received — paymentId={}", razorpay_payment_id);
        return new ModelAndView("payment-success");
    }
}