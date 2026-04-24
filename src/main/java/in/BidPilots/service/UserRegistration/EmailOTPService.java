package in.BidPilots.service.UserRegistration;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EmailOTPService — uses Resend HTTP API (not SMTP).
 *
 * Railway blocks all outbound SMTP (ports 465, 587).
 * Resend's REST API runs over HTTPS (port 443) which is always open.
 * No JavaMailSender, no Spring Mail dependency needed for sending.
 *
 * Resend free tier: 3,000 emails/month, 100/day.
 * API docs: https://resend.com/docs/api-reference/emails/send-email
 */
@Service
@Slf4j
public class EmailOTPService {

    // ── Resend config ──────────────────────────────────────────────────────────
    @Value("${resend.api.key}")
    private String resendApiKey;

    @Value("${resend.from.email:onboarding@resend.dev}")
    private String fromEmail;

    private static final String RESEND_API_URL = "https://api.resend.com/emails";

    // ── OTP config ─────────────────────────────────────────────────────────────
    private static final int OTP_LENGTH         = 6;
    private static final int OTP_EXPIRY_MINUTES = 10;
    private static final int MAX_ATTEMPTS       = 3;
    private static final int MAX_OTP_ENTRIES    = 50_000;
    private static final SecureRandom RNG       = new SecureRandom();

    // ── Shared HTTP client — reused across all requests ────────────────────────
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ── In-memory OTP store ────────────────────────────────────────────────────
    private static final Map<String, OTPData> otpStore = new ConcurrentHashMap<>();

    // ── Metrics ────────────────────────────────────────────────────────────────
    private final AtomicInteger totalEmailsSent    = new AtomicInteger(0);
    private final AtomicInteger totalEmailFailures = new AtomicInteger(0);

    // ─────────────────────────────────────────────────────────────
    // Inner data class
    // ─────────────────────────────────────────────────────────────

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class OTPData {
        private String        otp;
        private LocalDateTime expiry;
        private int           attempts;

        boolean isExpired()         { return LocalDateTime.now().isAfter(expiry); }
        boolean isLocked()          { return attempts >= MAX_ATTEMPTS; }
        void    incrementAttempts() { attempts++; }
    }

    // ─────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void init() {
        log.info("EmailOTPService initialized — fromEmail={} provider=Resend(HTTP)", fromEmail);
        startCleanupThread();
    }

    private void startCleanupThread() {
        Thread t = new Thread(() -> {
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
        t.setDaemon(true);
        t.setName("OTP-Cleanup");
        t.start();
    }

    // ─────────────────────────────────────────────────────────────
    // Generate + store OTP, fire async email
    // ─────────────────────────────────────────────────────────────

    public Map<String, Object> generateAndSendOTP(String email) {
        if (!StringUtils.hasText(email)) return errorResponse("Email is required.");

        email = email.trim().toLowerCase();
        Map<String, Object> response = new HashMap<>();

        try {
            String otp = generateSecureOTP();
            evictExpiredOTPs();

            // Store BEFORE sending — user can verify even if send is slow
            otpStore.put(email, new OTPData(otp,
                    LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES), 0));

            // Fire-and-forget via HTTP — never blocks the HTTP request thread
            sendOTPEmailAsync(email, otp);

            log.info("OTP stored and async HTTP send triggered for: {}", email);
            response.put("success", true);
            response.put("message", "OTP sent to " + email + ". Valid for " + OTP_EXPIRY_MINUTES + " minutes.");
            response.put("email", email);
            response.put("expiryMinutes", OTP_EXPIRY_MINUTES);

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
    // Async OTP email — Resend HTTP API
    // ─────────────────────────────────────────────────────────────

    @Async("emailTaskExecutor")
    public void sendOTPEmailAsync(String email, String otp) {
        String subject = "BidPilots Verification Code: " + otp;
        String text = String.format(
                "Your one-time verification code is:\n\n" +
                "  %s\n\n" +
                "This code is valid for %d minutes.\n\n" +
                "If you did not request this code, please ignore this email.\n\n" +
                "For security, never share this code with anyone.\n\n" +
                "— BidPilots Security Team",
                otp, OTP_EXPIRY_MINUTES);

        boolean sent = sendViaResend(email, subject, text);
        if (sent) {
            totalEmailsSent.incrementAndGet();
            log.info("OTP email delivered via Resend to: {}", email);
        } else {
            totalEmailFailures.incrementAndGet();
            log.error("OTP email failed for: {} — check Resend API key and sender domain", email);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Verify OTP
    // ─────────────────────────────────────────────────────────────

    public Map<String, Object> verifyOTP(String email, String otp) {
        if (!StringUtils.hasText(email) || !StringUtils.hasText(otp))
            return errorResponse("Email and OTP are both required.");

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
        if (!StringUtils.hasText(email)) return errorResponse("Email is required.");

        email = email.trim().toLowerCase();

        OTPData existing = otpStore.get(email);
        if (existing != null && existing.getExpiry() != null) {
            long minutesRemaining = Duration
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
            String text    = String.format(
                    "Dear %s,\n\n" +
                    "Welcome to BidPilots! Your registration is complete and your 30-day free trial is now active.\n\n" +
                    "Get started by:\n" +
                    "1. Logging into your account\n" +
                    "2. Setting up your business profile\n" +
                    "3. Exploring GeM bids matching your business\n\n" +
                    "Need help? Contact us at support@bidpilots.com\n\n" +
                    "Best regards,\n" +
                    "The BidPilots Team",
                    name);

            boolean sent = sendViaResend(toEmail, subject, text);
            if (sent) log.info("Welcome email sent to: {}", toEmail);
            else      log.warn("Welcome email failed for: {}", toEmail);
        } catch (Exception e) {
            log.error("Welcome email error for {}: {}", toEmail, e.getMessage());
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
    // Core: send via Resend HTTP API
    // ─────────────────────────────────────────────────────────────

    private boolean sendViaResend(String toEmail, String subject, String text) {
        try {
            // Escape JSON special characters in text
            String safeText = text
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");

            String json = String.format(
                    "{\"from\":\"%s\",\"to\":[\"%s\"],\"subject\":\"%s\",\"text\":\"%s\"}",
                    fromEmail, toEmail, subject, safeText);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RESEND_API_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + resendApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status == 200 || status == 201) {
                return true;
            } else {
                log.error("Resend API error — status={} body={}", status, response.body());
                return false;
            }

        } catch (Exception e) {
            log.error("Resend HTTP call failed: {}", e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    private String generateSecureOTP() {
        StringBuilder sb = new StringBuilder(OTP_LENGTH);
        sb.append(1 + RNG.nextInt(9)); // first digit never 0
        for (int i = 1; i < OTP_LENGTH; i++) sb.append(RNG.nextInt(10));
        return sb.toString();
    }

    private void evictExpiredOTPs() {
        try {
            otpStore.entrySet().removeIf(e -> e.getValue().isExpired());
            if (otpStore.size() > MAX_OTP_ENTRIES) {
                log.warn("OTP store exceeded {} entries — clearing", MAX_OTP_ENTRIES);
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

    private boolean isDevelopmentEnvironment() {
        String env = System.getenv("SPRING_PROFILES_ACTIVE");
        return "dev".equals(env) || "development".equals(env) || "local".equals(env);
    }
}
