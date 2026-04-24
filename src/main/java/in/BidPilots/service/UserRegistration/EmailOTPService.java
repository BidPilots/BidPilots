package in.BidPilots.service.UserRegistration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmailOTPService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    private static final int OTP_LENGTH         = 6;
    private static final int OTP_EXPIRY_MINUTES = 10;
    private static final int MAX_ATTEMPTS       = 3;
    private static final int MAX_OTP_ENTRIES    = 50_000;
    private static final SecureRandom RNG       = new SecureRandom();

    // In-memory OTP store. Replace with Redis for multi-instance deployments.
    private static final Map<String, OTPData> otpStore = new ConcurrentHashMap<>();

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class OTPData {
        private String        otp;
        private LocalDateTime expiry;
        private int           attempts;

        boolean isExpired() { return LocalDateTime.now().isAfter(expiry); }
        boolean isLocked()  { return attempts >= MAX_ATTEMPTS; }
    }

    // ─────────────────────────────────────────────────────────────
    // GENERATE AND SEND OTP
    // ─────────────────────────────────────────────────────────────

    public Map<String, Object> generateAndSendOTP(String email) {
        if (!StringUtils.hasText(email)) return error("Email is required.");
        Map<String, Object> res = new HashMap<>();
        try {
            String otp = generateSecureOTP();
            evictExpiredOTPs();
            otpStore.put(email, new OTPData(otp, LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES), 0));
            sendOTPEmail(email, otp);
            log.info("📧 OTP sent via Gmail to: {}", email);
            res.put("success",       true);
            res.put("message",       "OTP sent to " + email);
            res.put("email",         email);
            res.put("expiryMinutes", OTP_EXPIRY_MINUTES);
        } catch (MailAuthenticationException e) {
            log.error("❌ Gmail authentication failed — check App Password: {}", e.getMessage());
            res.put("success", false);
            res.put("message", "Email service authentication failed. Please contact support.");
        } catch (MailSendException e) {
            log.error("❌ Gmail send failed for {}: {}", email, e.getMessage());
            res.put("success", false);
            res.put("message", "Could not send OTP email. Please try again.");
        } catch (Exception e) {
            log.error("❌ generateAndSendOTP error for {}: {}", email, e.getMessage(), e);
            res.put("success", false);
            res.put("message", "Could not send OTP. Please try again.");
        }
        return res;
    }

    // ─────────────────────────────────────────────────────────────
    // VERIFY OTP
    // ─────────────────────────────────────────────────────────────

    public Map<String, Object> verifyOTP(String email, String otp) {
        if (!StringUtils.hasText(email) || !StringUtils.hasText(otp)) {
            return error("Email and OTP are both required.");
        }
        Map<String, Object> res = new HashMap<>();
        try {
            OTPData data = otpStore.get(email);
            if (data == null) {
                log.warn("No OTP found for email: {}", email);
                return error("No OTP found. Please request a new one.");
            }
            if (data.isExpired()) {
                otpStore.remove(email);
                log.warn("Expired OTP for email: {}", email);
                return error("OTP has expired. Please request a new one.");
            }
            if (data.isLocked()) {
                otpStore.remove(email);
                log.warn("Locked OTP for email: {}", email);
                return error("Too many failed attempts. Please request a new OTP.");
            }

            if (!data.getOtp().equals(otp.trim())) {
                data.setAttempts(data.getAttempts() + 1);
                otpStore.put(email, data);
                int left = MAX_ATTEMPTS - data.getAttempts();
                log.warn("Invalid OTP for email: {}, attempts left: {}", email, left);
                return error(left > 0
                    ? "Invalid OTP. " + left + " attempt" + (left == 1 ? "" : "s") + " remaining."
                    : "No attempts remaining. Please request a new OTP.");
            }

            otpStore.remove(email);
            log.info("✅ OTP verified for: {}", email);
            res.put("success", true);
            res.put("message", "OTP verified successfully.");
            res.put("email",   email);

        } catch (Exception e) {
            log.error("❌ verifyOTP error for {}: {}", email, e.getMessage(), e);
            res.put("success", false);
            res.put("message", "Verification failed. Please try again.");
        }
        return res;
    }

    // ─────────────────────────────────────────────────────────────
    // RESEND OTP
    // ─────────────────────────────────────────────────────────────

    public Map<String, Object> resendOTP(String email) {
        if (!StringUtils.hasText(email)) return error("Email is required.");
        Map<String, Object> res = new HashMap<>();
        try {
            otpStore.remove(email);
            String newOtp = generateSecureOTP();
            otpStore.put(email, new OTPData(newOtp, LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES), 0));
            sendOTPEmail(email, newOtp);
            log.info("📧 OTP resent via Gmail to: {}", email);
            res.put("success",       true);
            res.put("message",       "New OTP sent to " + email);
            res.put("expiryMinutes", OTP_EXPIRY_MINUTES);
        } catch (MailAuthenticationException e) {
            log.error("❌ Gmail authentication failed on resend: {}", e.getMessage());
            res.put("success", false);
            res.put("message", "Email service authentication failed. Please contact support.");
        } catch (MailSendException e) {
            log.error("❌ Gmail resend failed for {}: {}", email, e.getMessage());
            res.put("success", false);
            res.put("message", "Could not resend OTP. Please try again.");
        } catch (Exception e) {
            log.error("❌ resendOTP error for {}: {}", email, e.getMessage(), e);
            res.put("success", false);
            res.put("message", "Could not resend OTP. Please try again.");
        }
        return res;
    }

    // ─────────────────────────────────────────────────────────────
    // CHECK IF OTP EXISTS
    // ─────────────────────────────────────────────────────────────

    public boolean hasOTP(String email) {
        OTPData data = otpStore.get(email);
        return data != null && !data.isExpired();
    }

    // ─────────────────────────────────────────────────────────────
    // WELCOME EMAIL (best-effort — never throws)
    // ─────────────────────────────────────────────────────────────

    public void sendWelcomeEmail(String toEmail, String companyName) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromEmail);
            msg.setTo(toEmail);
            msg.setSubject("Welcome to BidPilots — Registration Successful");
            msg.setText(
                "Dear " + (StringUtils.hasText(companyName) ? companyName : "User") + ",\n\n" +
                "Your registration is complete. Welcome to BidPilots!\n\n" +
                "Your 30-day free trial is now active. Log in to start discovering GeM bids.\n\n" +
                "Regards,\nThe BidPilots Team"
            );
            mailSender.send(msg);
            log.info("✅ Welcome email sent to: {}", toEmail);
        } catch (Exception e) {
            log.warn("Welcome email failed for {}: {}", toEmail, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────

    private void sendOTPEmail(String toEmail, String otp) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(fromEmail);
        msg.setTo(toEmail);
        msg.setSubject("BidPilots Verification Code: " + otp);
        msg.setText(
            "Your one-time verification code is:\n\n" +
            "  " + otp + "\n\n" +
            "Valid for " + OTP_EXPIRY_MINUTES + " minutes.\n\n" +
            "If you did not request this, please ignore this email.\n\n" +
            "— BidPilots Security"
        );
        mailSender.send(msg);
    }

    private String generateSecureOTP() {
        StringBuilder sb = new StringBuilder(OTP_LENGTH);
        sb.append(1 + RNG.nextInt(9));
        for (int i = 1; i < OTP_LENGTH; i++) sb.append(RNG.nextInt(10));
        return sb.toString();
    }

    private void evictExpiredOTPs() {
        try {
            otpStore.entrySet().removeIf(e -> e.getValue().isExpired());
            if (otpStore.size() > MAX_OTP_ENTRIES) {
                log.warn("⚠️ OTP store exceeded {} — clearing all", MAX_OTP_ENTRIES);
                otpStore.clear();
            }
        } catch (Exception e) {
            log.error("evictExpiredOTPs error: {}", e.getMessage());
        }
    }

    private Map<String, Object> error(String message) {
        return Map.of("success", false, "message", message);
    }
}
