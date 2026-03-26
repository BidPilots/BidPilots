package in.BidPilots.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring's @Scheduled support.
 * Without this, the expireSubscriptions() cron job in SubscriptionService will never run.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}