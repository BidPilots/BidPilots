package in.BidPilots.service.UserRegistration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
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

    private static final int OTP_LENGTH = 6;
    private static final int OTP_EXPIRY_MINUTES = 10;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_OTP_ENTRIES = 50000;
    private static final SecureRandom RNG = new SecureRandom();

    // In-memory OTP store
    private static final Map<String, OTPData> otpStore = new ConcurrentHashMap<>();
    
    // Metrics for monitoring
    private final AtomicInteger totalEmailsSent = new AtomicInteger(0);
    private final AtomicInteger totalEmailFailures = new AtomicInteger(0);

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class OTPData {
        private String otp;
        private LocalDateTime expiry;
        private int attempts;

        boolean isExpired() { 
            return LocalDateTime.now().isAfter(expiry); 
        }
        
        boolean isLocked() { 
            return attempts >= MAX_ATTEMPTS; 
        }
        
        void incrementAttempts() { 
            attempts++; 
        }
    }

    @PostConstruct
    public void init() {
        log.info("EmailOTPService initialized");
        startCleanupScheduler();
    }

    private void startCleanupScheduler() {
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(60000); // Run every minute
                    evictExpiredOTPs();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Cleanup error: {}", e.getMessage());
                }
            }
        });
        cleanupThread.setDaemon(true);
        cleanupThread.setName("OTP-Cleanup");
        cleanupThread.start();
    }

    public Map<String, Object> generateAndSendOTP(String email) {
        if (!StringUtils.hasText(email)) {
            return errorResponse("Email is required.");
        }

        email = email.trim().toLowerCase();
        Map<String, Object> response = new HashMap<>();

        try {
            String otp = generateSecureOTP();
            evictExpiredOTPs();
            otpStore.put(email, new OTPData(otp, 
                LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES), 0));

            // Send email with retry logic (max 2 retries)
            boolean emailSent = sendEmailWithRetry(email, otp, 2);
            
            if (emailSent) {
                log.info("OTP sent successfully to: {}", email);
                response.put("success", true);
                response.put("message", "OTP sent to " + email);
                response.put("email", email);
                response.put("expiryMinutes", OTP_EXPIRY_MINUTES);
            } else {
                log.warn("OTP generated but email failed for: {}", email);
                response.put("success", true);
                response.put("message", "OTP generated. If you don't receive email, please try resend.");
                response.put("email", email);
                response.put("expiryMinutes", OTP_EXPIRY_MINUTES);
                response.put("delayed", true);
            }
            
            // Debug OTP only in development
            if (isDevelopmentEnvironment()) {
                response.put("debug_otp", otp);
            }
            
        } catch (Exception e) {
            log.error("Failed to generate OTP for {}: {}", email, e.getMessage(), e);
            totalEmailFailures.incrementAndGet();
            response.put("success", false);
            response.put("message", "Unable to process request. Please try again.");
        }
        
        return response;
    }

    private boolean sendEmailWithRetry(String email, String otp, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                sendOTPEmail(email, otp);
                totalEmailsSent.incrementAndGet();
                if (attempt > 1) {
                    log.info("Email sent on attempt {} for: {}", attempt, email);
                }
                return true;
            } catch (MailAuthenticationException e) {
                log.error("Authentication failed for {}: {}", email, e.getMessage());
                totalEmailFailures.incrementAndGet();
                return false;
            } catch (MailSendException e) {
                totalEmailFailures.incrementAndGet();
                if (attempt < maxRetries) {
                    try {
                        long delay = 2000 * attempt;
                        log.warn("Retry {} for {} in {}ms", attempt, email, delay);
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    log.error("All email attempts failed for {}", email, e);
                }
            } catch (MailException e) {
                totalEmailFailures.incrementAndGet();
                log.error("Mail error for {}: {}", email, e.getMessage());
                if (attempt == maxRetries) {
                    return false;
                }
            }
        }
        return false;
    }

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
            log.info("OTP verified for: {}", email);
            response.put("success", true);
            response.put("message", "OTP verified successfully.");
            response.put("email", email);

        } catch (Exception e) {
            log.error("OTP verification error for {}: {}", email, e.getMessage(), e);
            return errorResponse("Verification failed. Please try again.");
        }
        
        return response;
    }

    public Map<String, Object> resendOTP(String email) {
        if (!StringUtils.hasText(email)) {
            return errorResponse("Email is required.");
        }

        email = email.trim().toLowerCase();
        
        // Rate limiting: prevent spam
        OTPData existingData = otpStore.get(email);
        if (existingData != null) {
            // Simple rate limit - check if OTP was created recently
            // We don't have createdAt field, so we use expiry as proxy
            if (existingData.getExpiry() != null) {
                long minutesRemaining = java.time.Duration.between(
                    LocalDateTime.now(), existingData.getExpiry()).toMinutes();
                if (minutesRemaining > 8) { // OTP still has >8 minutes left
                    return errorResponse("Please wait before requesting another OTP.");
                }
            }
        }

        otpStore.remove(email);
        return generateAndSendOTP(email);
    }

    public boolean hasOTP(String email) {
        if (!StringUtils.hasText(email)) return false;
        OTPData data = otpStore.get(email.trim().toLowerCase());
        return data != null && !data.isExpired();
    }

    public void sendWelcomeEmail(String toEmail, String companyName) {
        if (!StringUtils.hasText(toEmail)) {
            log.warn("Cannot send welcome email: No email");
            return;
        }

        try {
            String name = StringUtils.hasText(companyName) ? companyName : "User";
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
                name
            );
            
            sendEmailWithRetry(toEmail, subject, content, 1); // One attempt for welcome email
            log.info("Welcome email sent to: {}", toEmail);
            
        } catch (Exception e) {
            log.error("Failed to send welcome email to {}: {}", toEmail, e.getMessage());
        }
    }

    private void sendOTPEmail(String toEmail, String otp) throws MailException {
        String subject = "BidPilots Verification Code: " + otp;
        String content = String.format(
            "Your one-time verification code is:\n\n" +
            "  %s\n\n" +
            "This code is valid for %d minutes.\n\n" +
            "If you did not request this code, please ignore this email.\n\n" +
            "For security, never share this code with anyone.\n\n" +
            "— BidPilots Security Team",
            otp, OTP_EXPIRY_MINUTES
        );
        
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(content);
        mailSender.send(message);
    }

    private void sendEmailWithRetry(String toEmail, String subject, String content, int maxRetries) throws MailException {
        MailException lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(fromEmail);
                message.setTo(toEmail);
                message.setSubject(subject);
                message.setText(content);
                mailSender.send(message);
                return;
            } catch (MailException e) {
                lastException = e;
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000 * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new MailSendException("Interrupted", ie);
                    }
                }
            }
        }
        
        throw lastException;
    }

    private String generateSecureOTP() {
        StringBuilder sb = new StringBuilder(OTP_LENGTH);
        sb.append(1 + RNG.nextInt(9));
        for (int i = 1; i < OTP_LENGTH; i++) {
            sb.append(RNG.nextInt(10));
        }
        return sb.toString();
    }

    private void evictExpiredOTPs() {
        try {
            int beforeSize = otpStore.size();
            otpStore.entrySet().removeIf(entry -> entry.getValue().isExpired());
            
            if (otpStore.size() > MAX_OTP_ENTRIES) {
                log.warn("OTP store exceeded limit, clearing");
                otpStore.clear();
            }
        } catch (Exception e) {
            log.error("Error evicting OTPs: {}", e.getMessage());
        }
    }

    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }

    private boolean isDevelopmentEnvironment() {
        String env = System.getenv("SPRING_PROFILES_ACTIVE");
        return env == null || env.equals("development") || env.equals("dev");
    }

    // Health check method
    public Map<String, Integer> getEmailMetrics() {
        Map<String, Integer> metrics = new HashMap<>();
        metrics.put("emailsSent", totalEmailsSent.get());
        metrics.put("emailFailures", totalEmailFailures.get());
        metrics.put("otpStoreSize", otpStore.size());
        return metrics;
    }
}
