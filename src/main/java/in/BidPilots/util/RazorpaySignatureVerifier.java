package in.BidPilots.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class RazorpaySignatureVerifier {

    @Value("${razorpay.key.secret}")
    private String razorpayKeySecret;

    public boolean verifyPaymentSignature(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature) {
        try {
            if (razorpaySignature == null || razorpaySignature.isBlank()) {
                log.warn("Razorpay signature is blank — rejecting");
                return false;
            }
            String payload = (razorpayOrderId != null && !razorpayOrderId.isBlank())
                    ? razorpayOrderId + "|" + razorpayPaymentId
                    : razorpayPaymentId;
            boolean valid = hmacSha256(payload, razorpayKeySecret).equals(razorpaySignature);
            if (!valid) log.warn("Razorpay signature mismatch — paymentId={}", razorpayPaymentId);
            return valid;
        } catch (Exception e) {
            log.error("Error verifying Razorpay signature: {}", e.getMessage(), e);
            return false;
        }
    }

    public boolean verifyWebhookSignature(byte[] webhookBody, String webhookSignature) {
        try {
            if (webhookSignature == null || webhookSignature.isBlank()) {
                log.warn("Webhook signature header is blank — rejecting");
                return false;
            }
            String payload  = new String(webhookBody, StandardCharsets.UTF_8);
            boolean valid   = hmacSha256(payload, razorpayKeySecret).equals(webhookSignature);
            if (!valid) log.warn("Razorpay webhook signature mismatch");
            return valid;
        } catch (Exception e) {
            log.error("Error verifying webhook signature: {}", e.getMessage(), e);
            return false;
        }
    }

    private String hmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}