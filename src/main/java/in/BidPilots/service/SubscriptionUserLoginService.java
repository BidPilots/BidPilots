package in.BidPilots.service;

import in.BidPilots.dto.UserLogin.UserLoginDTO;
import in.BidPilots.entity.User;
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

/**
 * Special login service for subscription page only.
 * Allows users with expired subscriptions to log in
 * so they can view plans and renew their subscription.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionUserLoginService {

    private final UserLoginRepository userLoginRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_TIME_DURATION_MINUTES = 30;

    /**
     * Login WITHOUT subscription expiry check.
     * This allows users with expired subscriptions to access the subscription page.
     */
    @Transactional
    public Map<String, Object> loginForSubscription(UserLoginDTO loginDTO) {
        Map<String, Object> response = new HashMap<>();
        try {
            String email = loginDTO.getEmail();
            log.info("🔐 Subscription page login attempt: {}", email);

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
                response.put("success", false);
                response.put("message", "Account locked. Try again after " + minutes + " minutes.");
                response.put("locked", true);
                response.put("unlockInMinutes", minutes);
                return response;
            }

            // 3. Email verified?
            if (!Boolean.TRUE.equals(user.getIsEmailVerified())) {
                response.put("success", false);
                response.put("message", "Please verify your email before logging in.");
                response.put("needsVerification", true);
                response.put("email", email);
                return response;
            }

            // 4. Account active?
            if (!Boolean.TRUE.equals(user.getIsActive())) {
                response.put("success", false);
                response.put("message", "Your account is inactive. Please contact support.");
                return response;
            }

            // 5. Password check
            if (!passwordEncoder.matches(loginDTO.getPassword(), user.getPassword())) {
                handleFailedLogin(user);
                int remaining = MAX_FAILED_ATTEMPTS - (user.getFailedAttempts() != null ? user.getFailedAttempts() : 0);
                response.put("success", false);
                response.put("message", "Invalid email or password");
                if (remaining > 0) response.put("remainingAttempts", remaining);
                return response;
            }

            // 6. Success - reset failed attempts and update login
            resetFailedAttempts(user);
            updateSuccessfulLogin(user);

            log.info("✅ Subscription page login successful for: {}", email);

            // Build response WITHOUT subscription check
            response.put("success", true);
            response.put("message", "Login successful");
            response.put("user", buildUserMap(user));
            response.put("redirectTo", "/subscription/plans");

        } catch (Exception e) {
            log.error("❌ Subscription login error: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Login failed: " + e.getMessage());
        }
        return response;
    }

    private Map<String, Object> buildUserMap(User user) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", user.getId());
        m.put("companyName", user.getCompanyName());
        m.put("email", user.getEmail());
        m.put("mobileNumber", user.getMobileNumber());
        m.put("isActive", user.getIsActive());
        m.put("isEmailVerified", user.getIsEmailVerified());
        m.put("role", user.getRole());
        m.put("lastLoginAt", user.getLastLoginAt());
        m.put("loginCount", user.getLoginCount());
        m.put("createdAt", user.getCreatedAt());
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
        LocalDateTime now = LocalDateTime.now();
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
        user.setLastLoginAt(LocalDateTime.now());
        user.setLoginCount((user.getLoginCount() != null ? user.getLoginCount() : 0) + 1);
        userLoginRepository.save(user);
    }
}