package in.BidPilots.service.UserRegistration;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmailOTPService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    private static final int OTP_LENGTH          = 6;
    private static final int OTP_EXPIRY_MINUTES  = 10;
    private static final int MAX_ATTEMPTS        = 3;
    private static final int MAX_OTP_ENTRIES     = 50_000;
    private static final SecureRandom RNG        = new SecureRandom();

    // In-memory OTP store
    private static final Map<String, OTPData> otpStore = new ConcurrentHashMap<>();

    // Metrics
    private final AtomicInteger totalEmailsSent     = new AtomicInteger(0);
    private final AtomicInteger totalEmailFailures  = new AtomicInteger(0);

    // ─────────────────────────────────────────────────────────────
    // Inner data class
    // ─────────────────────────────────────────────────────────────

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class OTPData {
        private String        otp;
        private LocalDateTime expiry;
        private int           attempts;

        boolean isExpired()          { return LocalDateTime.now().isAfter(expiry); }
        boolean isLocked()           { return attempts >= MAX_ATTEMPTS; }
        void    incrementAttempts()  { attempts++; }
    }

    // ─────────────────────────────────────────────────────────────
    // Init — start background cleanup thread
    // ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void init() {
        log.info("EmailOTPService initialized — fromEmail={}", fromEmail);
        Thread cleanupThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(60_000);
                    evictExpiredOTPs();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("OTP cleanup error: {}", e.getMessage());
                }
            }
        });
        cleanupThread.setDaemon(true);
        cleanupThread.setName("OTP-Cleanup");
        cleanupThread.start();
    }

    // ─────────────────────────────────────────────────────────────
    // Generate OTP (stores it) then fire email asynchronously
    // Returns immediately — does NOT block waiting for SMTP
    // ─────────────────────────────────────────────────────────────

    public Map<String, Object> generateAndSendOTP(String email) {
        if (!StringUtils.hasText(email)) {
            return errorResponse("Email is required.");
        }

        email = email.trim().toLowerCase();
        Map<String, Object> response = new HashMap<>();

        try {
            String otp = generateSecureOTP();
            evictExpiredOTPs();

            // Store OTP BEFORE attempting send — user can verify even if email is slow
            otpStore.put(email, new OTPData(otp,
                    LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES), 0));

            // Fire-and-forget async send — never blocks the HTTP request thread
            sendOTPEmailAsync(email, otp);

            log.info("OTP stored and async send triggered for: {}", email);
            response.put("success", true);
            response.put("message", "OTP sent to " + email + ". Valid for " + OTP_EXPIRY_MINUTES + " minutes.");
            response.put("email", email);
            response.put("expiryMinutes", OTP_EXPIRY_MINUTES);

            // SECURITY FIX: only expose OTP in explicitly non-production profiles
            if (isDevelopmentEnvironment()) {
                response.put("debug_otp", otp);
                log.debug("DEV MODE — OTP for {}: {}", email, otp);
            }

        } catch (Exception e) {
            log.error("Failed to generate OTP for {}: {}", email, e.getMessage(), e);
            totalEmailFailures.incrementAndGet();
            response.put("success", false);
            response.put("message", "Unable to process request. Please try again.");
        }

        return response;
    }

    // ─────────────────────────────────────────────────────────────
    // Async email dispatch — runs in Spring's task executor thread pool
    // @Async requires @EnableAsync on a @Configuration class
    // ─────────────────────────────────────────────────────────────

    @Async("emailTaskExecutor")
    public void sendOTPEmailAsync(String email, String otp) {
        try {
            sendOTPEmail(email, otp);
            totalEmailsSent.incrementAndGet();
            log.info("OTP email delivered to: {}", email);
        } catch (MailAuthenticationException e) {
            totalEmailFailures.incrementAndGet();
            log.error("SMTP authentication failed — check EMAIL_USERNAME / EMAIL_PASSWORD: {}", e.getMessage());
        } catch (MailSendException e) {
            totalEmailFailures.incrementAndGet();
            log.error("OTP email send failed for {} (SMTP connection issue — check port/host): {}", email, e.getMessage());
        } catch (MailException e) {
            totalEmailFailures.incrementAndGet();
            log.error("OTP email failed for {}: {}", email, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Verify OTP
    // ─────────────────────────────────────────────────────────────

    public Map<String, Object> verifyOTP(String email, String otp) {
        if (!StringUtils.hasText(email) || !StringUtils.hasText(otp)) {
            return errorResponse("Email and OTP are both required.");
        }

        email = email.trim().toLowerCase();
        Map<String, Object> response = new HashMap<>();

        try {
            OTPData data = otpStore.get(email);

            if (data == null) {
                log.warn("No OTP found for: {}", email);
                return errorResponse("No OTP found. Please request a new one.");
            }
            if (data.isExpired()) {
                otpStore.remove(email);
                log.warn("Expired OTP for: {}", email);
                return errorResponse("OTP has expired. Please request a new one.");
            }
            if (data.isLocked()) {
                otpStore.remove(email);
                log.warn("Locked OTP for: {}", email);
                return errorResponse("Too many failed attempts. Please request a new OTP.");
            }
            if (!data.getOtp().equals(otp.trim())) {
                data.incrementAttempts();
                otpStore.put(email, data);
                int left = MAX_ATTEMPTS - data.getAttempts();
                log.warn("Invalid OTP for: {}, attempts left: {}", email, left);
                return errorResponse(left > 0
                        ? "Invalid OTP. " + left + " attempt" + (left == 1 ? "" : "s") + " remaining."
                        : "No attempts remaining. Please request a new OTP.");
            }

            otpStore.remove(email);
            log.info("OTP verified successfully for: {}", email);
            response.put("success", true);
            response.put("message", "OTP verified successfully.");
            response.put("email", email);

        } catch (Exception e) {
            log.error("OTP verification error for {}: {}", email, e.getMessage(), e);
            return errorResponse("Verification failed. Please try again.");
        }

        return response;
    }

    // ─────────────────────────────────────────────────────────────
    // Resend OTP (rate-limited)
    // ─────────────────────────────────────────────────────────────

    public Map<String, Object> resendOTP(String email) {
        if (!StringUtils.hasText(email)) {
            return errorResponse("Email is required.");
        }

        email = email.trim().toLowerCase();

        OTPData existing = otpStore.get(email);
        if (existing != null && existing.getExpiry() != null) {
            long minutesRemaining = java.time.Duration
                    .between(LocalDateTime.now(), existing.getExpiry()).toMinutes();
            if (minutesRemaining > 8) {
                return errorResponse("Please wait before requesting another OTP.");
            }
        }

        otpStore.remove(email);
        return generateAndSendOTP(email);
    }

    // ─────────────────────────────────────────────────────────────
    // Welcome email (async, best-effort)
    // ─────────────────────────────────────────────────────────────

    @Async("emailTaskExecutor")
    public void sendWelcomeEmail(String toEmail, String companyName) {
        if (!StringUtils.hasText(toEmail)) {
            log.warn("sendWelcomeEmail: no email provided");
            return;
        }
        try {
            String name    = StringUtils.hasText(companyName) ? companyName : "User";
            String subject = "Welcome to BidPilots – Registration Successful";
            String content = String.format(
                    "Dear %s,\n\n" +
                    "Welcome to BidPilots! Your registration is complete and your 30-day free trial is now active.\n\n" +
                    "Get started by:\n" +
                    "1. Logging into your account\n" +
                    "2. Setting up your business profile\n" +
                    "3. Exploring GeM bids matching your business\n\n" +
                    "Need help? Contact our support team at support@bidpilots.com\n\n" +
                    "Best regards,\n" +
                    "The BidPilots Team",
                    name);

            sendGenericEmail(toEmail, subject, content);
            log.info("Welcome email sent to: {}", toEmail);
        } catch (Exception e) {
            log.error("Welcome email failed for {}: {}", toEmail, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────

    public boolean hasOTP(String email) {
        if (!StringUtils.hasText(email)) return false;
        OTPData data = otpStore.get(email.trim().toLowerCase());
        return data != null && !data.isExpired();
    }

    public Map<String, Integer> getEmailMetrics() {
        Map<String, Integer> metrics = new HashMap<>();
        metrics.put("emailsSent",    totalEmailsSent.get());
        metrics.put("emailFailures", totalEmailFailures.get());
        metrics.put("otpStoreSize",  otpStore.size());
        return metrics;
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    private void sendOTPEmail(String toEmail, String otp) throws MailException {
        String subject = "BidPilots Verification Code: " + otp;
        String content = String.format(
                "Your one-time verification code is:\n\n" +
                "  %s\n\n" +
                "This code is valid for %d minutes.\n\n" +
                "If you did not request this code, please ignore this email.\n\n" +
                "For security, never share this code with anyone.\n\n" +
                "— BidPilots Security Team",
                otp, OTP_EXPIRY_MINUTES);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(content);
        mailSender.send(message);
    }

    private void sendGenericEmail(String toEmail, String subject, String content) throws MailException {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(content);
        mailSender.send(message);
    }

    private String generateSecureOTP() {
        StringBuilder sb = new StringBuilder(OTP_LENGTH);
        sb.append(1 + RNG.nextInt(9)); // first digit never 0
        for (int i = 1; i < OTP_LENGTH; i++) {
            sb.append(RNG.nextInt(10));
        }
        return sb.toString();
    }

    private void evictExpiredOTPs() {
        try {
            otpStore.entrySet().removeIf(e -> e.getValue().isExpired());
            if (otpStore.size() > MAX_OTP_ENTRIES) {
                log.warn("OTP store exceeded {} entries — clearing all", MAX_OTP_ENTRIES);
                otpStore.clear();
            }
        } catch (Exception e) {
            log.error("evictExpiredOTPs error: {}", e.getMessage());
        }
    }

    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        r.put("message", message);
        return r;
    }

    /**
     * SECURITY FIX: was returning true when env var is null (i.e. always in prod).
     * Now only returns true for explicitly dev profiles.
     */
    private boolean isDevelopmentEnvironment() {
        String env = System.getenv("SPRING_PROFILES_ACTIVE");
        return "dev".equals(env) || "development".equals(env) || "local".equals(env);
    }
}
