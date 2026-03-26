package in.BidPilots.service;

import in.BidPilots.dto.UserLogin.UserLoginDTO;
import in.BidPilots.entity.Subscription;
import in.BidPilots.entity.User;
import in.BidPilots.enums.PlanDuration;           // unified — single source of truth for plan metadata
import in.BidPilots.repository.SubscriptionRepository;
import in.BidPilots.repository.UserLoginRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserLoginService {

    private final UserLoginRepository    userLoginRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final BCryptPasswordEncoder  passwordEncoder;

    private static final int  MAX_FAILED_ATTEMPTS        = 5;
    private static final long LOCK_TIME_DURATION_MINUTES = 30;

    // ─────────────────────────────────────────────────────────────
    // LOGIN
    // ─────────────────────────────────────────────────────────────
    @Transactional
    public Map<String, Object> login(UserLoginDTO loginDTO) {
        Map<String, Object> response = new HashMap<>();
        try {
            String email = loginDTO.getEmail();
            log.info("🔐 Login attempt: {}", email);

            // 1. Find user
            Optional<User> userOpt = userLoginRepository.findByEmail(email);
            if (userOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Invalid email or password");
                return response;
            }
            User user = userOpt.get();

            // 2. Account locked?
            if (isAccountLocked(user)) {
                long minutes = calculateUnlockTimeMinutes(user);
                response.put("success",        false);
                response.put("message",        "Account locked. Try again after " + minutes + " minutes.");
                response.put("locked",          true);
                response.put("unlockInMinutes", minutes);
                return response;
            }

            // 3. Email verified?
            if (!Boolean.TRUE.equals(user.getIsEmailVerified())) {
                response.put("success",           false);
                response.put("message",           "Please verify your email before logging in.");
                response.put("needsVerification", true);
                response.put("email",             email);
                return response;
            }

            // 4. Account active?
            if (!Boolean.TRUE.equals(user.getIsActive())) {
                response.put("success", false);
                response.put("message", "Your account is inactive. Please contact support.");
                return response;
            }

            // 5. Subscription check — read from subscriptions table, NOT from User fields
            Optional<Subscription> subOpt = subscriptionRepository.findByUserId(user.getId());

            if (subOpt.isEmpty() || !subOpt.get().isCurrentlyActive()) {
                log.warn("🔒 Login blocked — no active subscription for: {}", email);

                String expiredDate = subOpt
                        .filter(s -> s.getEndDate() != null)
                        .map(s -> s.getEndDate().toString())
                        .orElse("N/A");

                response.put("success",             false);
                response.put("message",             "Your subscription has expired. Please renew to continue.");
                response.put("subscriptionExpired", true);
                response.put("userId",              user.getId());
                response.put("email",               user.getEmail());
                response.put("expiredDate",         expiredDate);
                response.put("renewUrl",            "/api/subscriptions/plans");
                return response;
            }

            Subscription sub = subOpt.get();

            // 6. Password check
            if (!passwordEncoder.matches(loginDTO.getPassword(), user.getPassword())) {
                handleFailedLogin(user);
                int remaining = MAX_FAILED_ATTEMPTS
                        - (user.getFailedAttempts() != null ? user.getFailedAttempts() : 0);
                response.put("success", false);
                response.put("message", "Invalid email or password");
                if (remaining > 0) response.put("remainingAttempts", remaining);
                return response;
            }

            // 7. Success
            resetFailedAttempts(user);
            updateSuccessfulLogin(user);

            log.info("✅ Login success: {} — plan={} daysLeft={}",
                    email, sub.getPlanDuration().getDisplayName(), sub.daysRemaining());

            response.put("success",    true);
            response.put("message",    "Login successful");
            response.put("user",       buildUserMap(user, sub));
            response.put("redirectTo", "ADMIN".equalsIgnoreCase(user.getRole())
                    ? "/api/admin/dashboard"
                    : "/api/user/dashboard");

        } catch (Exception e) {
            log.error("❌ Login error: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Login failed: " + e.getMessage());
        }
        return response;
    }

    // ─────────────────────────────────────────────────────────────
    // GET USER BY EMAIL
    // ─────────────────────────────────────────────────────────────
    public Map<String, Object> getUserByEmail(String email) {
        Map<String, Object> response = new HashMap<>();
        try {
            Optional<User> userOpt = userLoginRepository.findByEmail(email);
            if (userOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "User not found");
                return response;
            }
            User user = userOpt.get();
            Optional<Subscription> subOpt = subscriptionRepository.findByUserId(user.getId());
            response.put("success", true);
            response.put("user",    buildUserMap(user, subOpt.orElse(null)));
        } catch (Exception e) {
            log.error("❌ getUserByEmail error: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Error: " + e.getMessage());
        }
        return response;
    }

    // ─────────────────────────────────────────────────────────────
    // CHECK EXISTS
    // ─────────────────────────────────────────────────────────────
    public Map<String, Object> checkUserExists(String email) {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("exists",  userLoginRepository.existsByEmail(email));
            response.put("email",   email);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Error: " + e.getMessage());
        }
        return response;
    }

    // ─────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds the JSON user map sent to the frontend.
     * Subscription data always comes from the Subscription entity — never User fields.
     */
    private Map<String, Object> buildUserMap(User user, Subscription sub) {
        Map<String, Object> m = new HashMap<>();

        // ── User fields (all exist on User entity) ──
        m.put("id",              user.getId());
        m.put("companyName",     user.getCompanyName());
        m.put("email",           user.getEmail());
        m.put("mobileNumber",    user.getMobileNumber());
        m.put("isActive",        user.getIsActive());
        m.put("isEmailVerified", user.getIsEmailVerified());
        m.put("role",            user.getRole());
        m.put("lastLoginAt",     user.getLastLoginAt());
        m.put("loginCount",      user.getLoginCount());
        m.put("createdAt",       user.getCreatedAt());

        // ── Subscription fields ──────────────────────────────────────────────────────
        // All plan metadata (price, duration, display name) comes from the unified
        // PlanDuration enum. The Subscription entity is the runtime source for dates
        // and status; PlanDuration is the static source for everything else.
        if (sub != null) {
            PlanDuration plan = sub.getPlanDuration();       // the ONE source of plan metadata

            m.put("subscriptionStatus",    sub.getStatus().name());
            m.put("currentPlan",           plan.name());
            m.put("planDisplayName",       plan.getDisplayName());
            m.put("planDurationDays",      plan.getDurationDays());
            m.put("planPriceRupees",       plan.getPriceRupees());
            m.put("planPricePerDay",       plan.getPricePerDay());
            m.put("isFreeplan",            plan.isFree());
            m.put("subscriptionStartDate", sub.getStartDate());
            m.put("subscriptionEndDate",   sub.getEndDate());
            m.put("daysRemaining",         sub.daysRemaining());
            m.put("isInTrial",             sub.isInTrialPeriod());
            m.put("hasActiveSubscription", sub.isCurrentlyActive());
            m.put("amountPaidRupees",      sub.getAmountPaidRupees());
        } else {
            m.put("subscriptionStatus",    "NONE");
            m.put("currentPlan",           "NONE");
            m.put("planDisplayName",       "No Plan");
            m.put("planDurationDays",      0);
            m.put("planPriceRupees",       0);
            m.put("planPricePerDay",       java.math.BigDecimal.ZERO);
            m.put("isFreeplan",            false);
            m.put("subscriptionStartDate", null);
            m.put("subscriptionEndDate",   null);
            m.put("daysRemaining",         0);
            m.put("isInTrial",             false);
            m.put("hasActiveSubscription", false);
            m.put("amountPaidRupees",      0);
        }
        return m;
    }

    private boolean isAccountLocked(User user) {
        if (user.getLockTime() == null) return false;
        return LocalDateTime.now()
                .isBefore(user.getLockTime().plusMinutes(LOCK_TIME_DURATION_MINUTES));
    }

    private long calculateUnlockTimeMinutes(User user) {
        if (user.getLockTime() == null) return 0;
        LocalDateTime unlock = user.getLockTime().plusMinutes(LOCK_TIME_DURATION_MINUTES);
        LocalDateTime now    = LocalDateTime.now();
        return now.isBefore(unlock) ? java.time.Duration.between(now, unlock).toMinutes() : 0;
    }

    @Transactional
    protected void handleFailedLogin(User user) {
        int attempts = user.getFailedAttempts() != null ? user.getFailedAttempts() + 1 : 1;
        user.setFailedAttempts(attempts);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            user.setLockTime(LocalDateTime.now());
            log.warn("🔒 Account locked: {} after {} failed attempts", user.getEmail(), attempts);
        }
        userLoginRepository.save(user);
    }

    @Transactional
    protected void resetFailedAttempts(User user) {
        user.setFailedAttempts(0);
        user.setLockTime(null);
        userLoginRepository.save(user);
    }

    @Transactional
    protected void updateSuccessfulLogin(User user) {
        userLoginRepository.updateLastLogin(user.getId(), LocalDateTime.now());
    }
}