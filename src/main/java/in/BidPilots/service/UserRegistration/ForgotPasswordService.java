package in.BidPilots.service.UserRegistration;

import in.BidPilots.entity.User;
import in.BidPilots.repository.UserRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
<<<<<<< HEAD
 * PRODUCTION FIX — ForgotPasswordService
 *
 * ORIGINAL PROBLEMS:
 *
 *  1. SECURITY — resetPassword() trusted the OTP sent by the client in the
 *     request body. After verifyResetOTP() consumed the OTP from EmailOTPService,
 *     the reset step had NO actual server-side verification. Any client could
 *     skip the OTP step and POST any value as "otp" to reset a password.
 *
 *  2. SECURITY — verifyResetOTP() returned the raw OTP value in the response
 *     body ("otp": "123456"). The frontend then forwarded it back in the reset
 *     request. This made the OTP visible in browser history, network logs, etc.
 *
 * FIX:
 *   - After OTP verification, issue a short-lived server-side "reset token"
 *     (a UUID stored in resetStorage with a 10-minute TTL).
 *   - The client sends this reset token instead of the OTP in the reset step.
 *   - resetPassword() validates the token server-side and never re-accepts the OTP.
 *   - The OTP itself is never returned in any response after the verify step.
=======
 * ForgotPasswordService — production-ready
 *
 * Security model:
 *  Step 1 — sendPasswordResetOTP()  → generates & sends OTP via EmailOTPService
 *  Step 2 — verifyResetOTP()        → verifies OTP, issues a short-lived UUID reset token
 *  Step 3 — resetPassword()         → accepts reset token (NOT the OTP), updates password
 *
 * The OTP is never returned in any response after verification.
 * The reset token is a server-side UUID — it is single-use and expires in 10 minutes.
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ForgotPasswordService {

    private final UserRegistrationRepository userRepository;
    private final EmailOTPService            emailOTPService;
    private final BCryptPasswordEncoder      passwordEncoder;

<<<<<<< HEAD
    /** Key = email, Value = ResetData (tracks OTP request state) */
    private static final Map<String, ResetData> otpStorage   = new ConcurrentHashMap<>();
    /** Key = resetToken (UUID), Value = email (issued after OTP verified) */
=======
    /** email → ResetData (tracks OTP request state) */
    private static final Map<String, ResetData> otpStorage   = new ConcurrentHashMap<>();
    /** resetToken (UUID) → "email|expiryISO" */
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b
    private static final Map<String, String>    tokenStorage = new ConcurrentHashMap<>();

    private static final int OTP_EXPIRY_MINUTES   = 15;
    private static final int TOKEN_EXPIRY_MINUTES = 10;
    private static final int MAX_OTP_ATTEMPTS     = 3;
    private static final int MAX_STORE_SIZE       = 10_000;

<<<<<<< HEAD
=======
    private static final String PASSWORD_REGEX =
            "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=!])(?=\\S+$).{8,}$";

>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b
    // ─────────────────────────────────────────────────────────────
    // STEP 1 — Send OTP for password reset
    // ─────────────────────────────────────────────────────────────

    public Map<String, Object> sendPasswordResetOTP(String email) {
        Map<String, Object> response = new HashMap<>();
        try {
<<<<<<< HEAD
            if (!StringUtils.hasText(email)) return error("Email is required");

            email = email.trim().toLowerCase();
            log.info("📧 Password reset requested for: {}", email);

            Optional<User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isEmpty()) {
                // Don't reveal whether email exists — prevents user enumeration
=======
            if (!StringUtils.hasText(email)) return error("Email is required.");

            email = email.trim().toLowerCase();
            log.info("Password reset requested for: {}", email);

            Optional<User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isEmpty()) {
                // Anti-enumeration: don't reveal whether this email exists
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b
                response.put("success", true);
                response.put("message", "If an account exists with this email, you will receive an OTP.");
                return response;
            }

            User user = userOpt.get();
<<<<<<< HEAD

            if (!Boolean.TRUE.equals(user.getIsEmailVerified())) {
                return error("Please verify your email first before resetting your password.");
            }

            // Clear any existing state for this email
            otpStorage.remove(email);
            tokenStorage.values().remove(email); // in case an old token exists

            Map<String, Object> otpResult = emailOTPService.generateAndSendOTP(email);
            if (!Boolean.TRUE.equals(otpResult.get("success"))) {
                return error(otpResult.getOrDefault("message", "Failed to send OTP").toString());
            }

            otpStorage.put(email, new ResetData(
                    LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES), 0));

            evictExpired();

            log.info("✅ Password reset OTP sent to: {}", email);
=======
            if (!Boolean.TRUE.equals(user.getIsEmailVerified()))
                return error("Please verify your email first before resetting your password.");

            // Clear any stale state
            otpStorage.remove(email);
            tokenStorage.values().remove(email);

            // generateAndSendOTP is now async — returns immediately
            Map<String, Object> otpResult = emailOTPService.generateAndSendOTP(email);
            if (!Boolean.TRUE.equals(otpResult.get("success")))
                return error(otpResult.getOrDefault("message", "Failed to send OTP.").toString());

            otpStorage.put(email, new ResetData(LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES), 0));
            evictExpired();

            log.info("Password reset OTP dispatched (async) to: {}", email);
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b
            response.put("success", true);
            response.put("message", "OTP sent to your email. Valid for " + OTP_EXPIRY_MINUTES + " minutes.");
            response.put("email", email);

        } catch (Exception e) {
<<<<<<< HEAD
            log.error("❌ sendPasswordResetOTP: {}", e.getMessage(), e);
=======
            log.error("sendPasswordResetOTP failed: {}", e.getMessage(), e);
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b
            return error("Failed to process request. Please try again.");
        }
        return response;
    }

    // ─────────────────────────────────────────────────────────────
<<<<<<< HEAD
    // STEP 2 — Verify OTP → issue a server-side reset token
=======
    // STEP 2 — Verify OTP → issue server-side reset token
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b
    // ─────────────────────────────────────────────────────────────

    public Map<String, Object> verifyResetOTP(String email, String otp) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!StringUtils.hasText(email) || !StringUtils.hasText(otp))
<<<<<<< HEAD
                return error("Email and OTP are required");

            email = email.trim().toLowerCase();
            log.info("🔐 Password reset OTP verify for: {}", email);
=======
                return error("Email and OTP are required.");

            email = email.trim().toLowerCase();
            log.info("Password reset OTP verification for: {}", email);
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b

            ResetData data = otpStorage.get(email);
            if (data == null)
                return error("No OTP request found. Please request a new OTP.");
            if (data.isExpired()) {
                otpStorage.remove(email);
                return error("OTP has expired. Please request a new one.");
            }
            if (data.isLocked()) {
                otpStorage.remove(email);
                return error("Too many failed attempts. Please request a new OTP.");
            }

            Map<String, Object> otpResult = emailOTPService.verifyOTP(email, otp);
            if (!Boolean.TRUE.equals(otpResult.get("success"))) {
                data.incrementAttempts();
                otpStorage.put(email, data);
                int left = MAX_OTP_ATTEMPTS - data.getAttempts();
                log.warn("Invalid OTP for {}, attempts left: {}", email, left);
                return error(left > 0
                        ? "Invalid OTP. " + left + " attempt" + (left == 1 ? "" : "s") + " remaining."
                        : "No attempts remaining. Please request a new OTP.");
            }

<<<<<<< HEAD
            // OTP verified — issue a short-lived server-side reset token
            otpStorage.remove(email);
            String resetToken = UUID.randomUUID().toString();
            tokenStorage.put(resetToken, email + "|" +
                    LocalDateTime.now().plusMinutes(TOKEN_EXPIRY_MINUTES));

            log.info("✅ OTP verified for: {} — reset token issued", email);
            response.put("success", true);
            response.put("message", "OTP verified. You may now set a new password.");
            response.put("email", email);
            response.put("resetToken", resetToken); // short-lived UUID, NOT the OTP

        } catch (Exception e) {
            log.error("❌ verifyResetOTP: {}", e.getMessage(), e);
=======
            // OTP verified — issue short-lived single-use reset token
            otpStorage.remove(email);
            String resetToken = UUID.randomUUID().toString();
            tokenStorage.put(resetToken,
                    email + "|" + LocalDateTime.now().plusMinutes(TOKEN_EXPIRY_MINUTES));
            evictExpired();

            log.info("OTP verified for: {} — reset token issued", email);
            response.put("success", true);
            response.put("message", "OTP verified. You may now set a new password.");
            response.put("email", email);
            response.put("resetToken", resetToken); // short-lived UUID — NOT the OTP

        } catch (Exception e) {
            log.error("verifyResetOTP failed: {}", e.getMessage(), e);
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b
            return error("Verification failed. Please try again.");
        }
        return response;
    }

    // ─────────────────────────────────────────────────────────────
<<<<<<< HEAD
    // STEP 3 — Reset password using the server-side token
=======
    // STEP 3 — Reset password using server-side token
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b
    // ─────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> resetPassword(String email,
                                              String resetToken,
                                              String newPassword,
                                              String confirmPassword) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!StringUtils.hasText(email) || !StringUtils.hasText(resetToken))
<<<<<<< HEAD
                return error("Email and reset token are required");

            email = email.trim().toLowerCase();
            log.info("🔐 Password reset for: {}", email);

            if (!StringUtils.hasText(newPassword) || !newPassword.equals(confirmPassword))
                return error("Passwords do not match");

            // Validate password strength
            String strongPwd = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=])(?=\\S+$).{8,}$";
            if (!newPassword.matches(strongPwd))
                return error("Password must be 8+ characters with uppercase, lowercase, number, and special character");

            // Validate the server-side reset token
=======
                return error("Email and reset token are required.");
            if (!StringUtils.hasText(newPassword) || !newPassword.equals(confirmPassword))
                return error("Passwords do not match.");
            if (!newPassword.matches(PASSWORD_REGEX))
                return error("Password must be 8+ characters and include uppercase, lowercase, number, and special character (@#$%^&+=!).");

            email = email.trim().toLowerCase();
            log.info("Password reset (step 3) for: {}", email);

            // Validate reset token
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b
            String tokenEntry = tokenStorage.get(resetToken);
            if (tokenEntry == null)
                return error("Invalid or expired reset token. Please start over.");

<<<<<<< HEAD
            String[] parts = tokenEntry.split("\\|");
            if (parts.length != 2)
                return error("Malformed reset token. Please start over.");

            String tokenEmail  = parts[0];
            LocalDateTime expiry = LocalDateTime.parse(parts[1]);
=======
            String[] parts = tokenEntry.split("\\|", 2);
            if (parts.length != 2)
                return error("Malformed reset token. Please start over.");

            String        tokenEmail = parts[0];
            LocalDateTime expiry     = LocalDateTime.parse(parts[1]);
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b

            if (!tokenEmail.equals(email))
                return error("Token does not match the provided email.");
            if (LocalDateTime.now().isAfter(expiry)) {
                tokenStorage.remove(resetToken);
                return error("Reset token has expired. Please start over.");
            }

            // Find and update user
            Optional<User> userOpt = userRepository.findByEmail(email);
<<<<<<< HEAD
            if (userOpt.isEmpty()) return error("User not found");
=======
            if (userOpt.isEmpty()) return error("User not found.");
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b

            User user = userOpt.get();
            if (!Boolean.TRUE.equals(user.getIsEmailVerified()))
                return error("Please verify your email before resetting your password.");

            user.setPassword(passwordEncoder.encode(newPassword));
            user.setFailedAttempts(0);
            user.setLockTime(null);
            userRepository.save(user);

<<<<<<< HEAD
            // Consume the token — single-use
            tokenStorage.remove(resetToken);

            log.info("✅ Password reset successful for: {}", email);
=======
            // Consume token — single use
            tokenStorage.remove(resetToken);

            log.info("Password reset successful for: {}", email);
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b
            response.put("success", true);
            response.put("message", "Password reset successfully. You can now log in with your new password.");
            response.put("redirectTo", "/api/users/login-form");

        } catch (Exception e) {
<<<<<<< HEAD
            log.error("❌ resetPassword: {}", e.getMessage(), e);
=======
            log.error("resetPassword failed: {}", e.getMessage(), e);
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b
            return error("Failed to reset password. Please try again.");
        }
        return response;
    }

    // ─────────────────────────────────────────────────────────────
    // Resend OTP
    // ─────────────────────────────────────────────────────────────

    public Map<String, Object> resendPasswordResetOTP(String email) {
        Map<String, Object> response = new HashMap<>();
        try {
<<<<<<< HEAD
            if (!StringUtils.hasText(email)) return error("Email is required");
            email = email.trim().toLowerCase();
            log.info("🔄 Resend password reset OTP for: {}", email);

            // Silently succeed for non-existent emails (anti-enumeration)
=======
            if (!StringUtils.hasText(email)) return error("Email is required.");
            email = email.trim().toLowerCase();
            log.info("Resend password reset OTP for: {}", email);

            // Anti-enumeration
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b
            if (!userRepository.existsByEmail(email)) {
                response.put("success", true);
                response.put("message", "If an account exists with this email, you will receive an OTP.");
                return response;
            }

            otpStorage.remove(email);

            Map<String, Object> otpResult = emailOTPService.resendOTP(email);
            if (Boolean.TRUE.equals(otpResult.get("success"))) {
                otpStorage.put(email, new ResetData(
                        LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES), 0));
                evictExpired();
<<<<<<< HEAD
                log.info("✅ Resent password reset OTP to: {}", email);
                response.put("success", true);
                response.put("message", "New OTP sent. Valid for " + OTP_EXPIRY_MINUTES + " minutes.");
            } else {
                return error(otpResult.getOrDefault("message", "Failed to resend OTP").toString());
            }

        } catch (Exception e) {
            log.error("❌ resendPasswordResetOTP: {}", e.getMessage(), e);
=======
                log.info("Resent password reset OTP to: {}", email);
                response.put("success", true);
                response.put("message", "New OTP sent. Valid for " + OTP_EXPIRY_MINUTES + " minutes.");
            } else {
                return error(otpResult.getOrDefault("message", "Failed to resend OTP.").toString());
            }

        } catch (Exception e) {
            log.error("resendPasswordResetOTP failed: {}", e.getMessage(), e);
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b
            return error("Failed to resend OTP. Please try again.");
        }
        return response;
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    private void evictExpired() {
        try {
            otpStorage.entrySet().removeIf(e -> e.getValue().isExpired());
            tokenStorage.entrySet().removeIf(e -> {
<<<<<<< HEAD
                String[] p = e.getValue().split("\\|");
                return p.length != 2 || LocalDateTime.now().isAfter(LocalDateTime.parse(p[1]));
            });
            if (otpStorage.size() > MAX_STORE_SIZE) {
                log.warn("⚠️ otpStorage exceeded limit — clearing");
                otpStorage.clear();
            }
            if (tokenStorage.size() > MAX_STORE_SIZE) {
                log.warn("⚠️ tokenStorage exceeded limit — clearing");
=======
                String[] p = e.getValue().split("\\|", 2);
                return p.length != 2 || LocalDateTime.now().isAfter(LocalDateTime.parse(p[1]));
            });
            if (otpStorage.size() > MAX_STORE_SIZE) {
                log.warn("otpStorage exceeded limit — clearing");
                otpStorage.clear();
            }
            if (tokenStorage.size() > MAX_STORE_SIZE) {
                log.warn("tokenStorage exceeded limit — clearing");
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b
                tokenStorage.clear();
            }
        } catch (Exception e) {
            log.error("evictExpired error: {}", e.getMessage());
        }
    }

    private Map<String, Object> error(String msg) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        r.put("message", msg);
        return r;
    }

    // ─────────────────────────────────────────────────────────────
<<<<<<< HEAD
    // Internal data class
=======
    // Inner data class
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b
    // ─────────────────────────────────────────────────────────────

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class ResetData {
        private LocalDateTime expiry;
        private int           attempts;

<<<<<<< HEAD
        boolean isExpired() { return LocalDateTime.now().isAfter(expiry); }
        boolean isLocked()  { return attempts >= MAX_OTP_ATTEMPTS; }
        void    incrementAttempts() { attempts++; }
    }
}
=======
        boolean isExpired()         { return LocalDateTime.now().isAfter(expiry); }
        boolean isLocked()          { return attempts >= MAX_OTP_ATTEMPTS; }
        void    incrementAttempts() { attempts++; }
    }
}
>>>>>>> 965c0b6361b4ea133a24474a56fb83b2822c763b
