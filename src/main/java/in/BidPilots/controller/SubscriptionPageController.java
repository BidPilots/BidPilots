package in.BidPilots.controller;

import in.BidPilots.service.UserLoginService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

/**
 * Serves HTML pages for the subscription module.
 * MUST be @Controller — NOT @RestController.
 *
 * SecurityConfig must have:
 *   .requestMatchers("/subscription/plans").permitAll()
 *   .requestMatchers("/payment/callback").permitAll()
 *   .requestMatchers("/api/subscriptions/plans").permitAll()
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class SubscriptionPageController {

    private final UserLoginService userLoginService;

    // ─── Subscription Plans HTML Page ─────────────────────────────────────────

    @GetMapping("/subscription/plans")
    public ModelAndView subscriptionPlansPage(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String email,
            @RequestParam(required = false, defaultValue = "false") boolean expired) {

        ModelAndView mav = new ModelAndView("subscription-plans");

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !auth.getPrincipal().equals("anonymousUser")) {
            Map<String, Object> userResponse = userLoginService.getUserByEmail(auth.getName());
            if (Boolean.TRUE.equals(userResponse.get("success"))) {
                mav.addObject("user", userResponse.get("user"));
            }
        }

        if (userId != null) mav.addObject("userId",    userId);
        if (email  != null) mav.addObject("userEmail", email);
        if (expired)        mav.addObject("subscriptionExpired", true);

        log.info("📄 Subscription plans page — userId={}, expired={}", userId, expired);
        return mav;
    }

    // ─── Payment Callback Page ─────────────────────────────────────────────────
    /**
     * Razorpay redirects back to this URL after payment with:
     *   ?razorpay_payment_id=xxx&razorpay_order_id=xxx&razorpay_signature=xxx
     *
     * Set this as your Razorpay payment link callback URL:
     *   http://YOUR_SERVER/payment/callback
     *
     * The page JS reads the params, calls /api/subscriptions/payment/success,
     * verifies signature, activates the subscription, then redirects to login.
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