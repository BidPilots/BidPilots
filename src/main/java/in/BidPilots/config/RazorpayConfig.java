package in.BidPilots.config;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Initialises the Razorpay SDK client once at startup.
 *
 * Previously, SubscriptionService was injecting RazorpayClient but there was
 * no @Bean that created it, causing an immediate NoSuchBeanDefinitionException
 * on startup and preventing the entire application from launching.
 *
 * Providing the client as a singleton here:
 *   - Validates credentials at startup (fast-fail)
 *   - Avoids recreating/re-authenticating the client on every payment request
 *   - Eliminates the "Authentication key was missing" errors
 */
@Configuration
@Slf4j
public class RazorpayConfig {

    @Value("${razorpay.key.id}")
    private String keyId;

    @Value("${razorpay.key.secret}")
    private String keySecret;

    @Bean
    public RazorpayClient razorpayClient() throws RazorpayException {
        if (keyId == null || keyId.isBlank() || keySecret == null || keySecret.isBlank()) {
            throw new IllegalStateException(
                "Razorpay credentials are not configured. " +
                "Please set razorpay.key.id and razorpay.key.secret in application.properties."
            );
        }
        log.info("✅ RazorpayClient initialised — keyId={}", keyId);
        return new RazorpayClient(keyId, keySecret);
    }
}