package in.BidPilots.scheduler;

import in.BidPilots.entity.Subscription;
import in.BidPilots.enums.SubscriptionStatus;
import in.BidPilots.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class SubscriptionScheduler {

    private final SubscriptionRepository subscriptionRepository;
    // Inject EmailService here when ready:
    // private final EmailService emailService;

    // ─────────────────────────────────────────────────────────────────────────
    // Daily 9:00 AM — Mark TRIAL/ACTIVE as EXPIRED if end date passed
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 9 * * *")
    @Transactional
    public void expireStaleSubscriptions() {
        List<Subscription> expired =
                subscriptionRepository.findExpiredButNotMarked(LocalDateTime.now());

        if (expired.isEmpty()) {
            log.info("✅ [Scheduler] No subscriptions to expire");
            return;
        }

        expired.forEach(s -> {
            log.info("⏰ [Scheduler] Expiring id={} userId={} was={} endDate={}",
                    s.getId(), s.getUserId(), s.getStatus(), s.getEndDate());
            s.setStatus(SubscriptionStatus.EXPIRED);
        });

        subscriptionRepository.saveAll(expired);
        log.info("✅ [Scheduler] Expired {} subscription(s)", expired.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Daily 10:00 AM — Warn trial users with ≤ 7 days left
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 10 * * *")
    public void sendTrialExpiryWarnings() {
        LocalDateTime now      = LocalDateTime.now();
        LocalDateTime sevenDay = now.plusDays(7);

        List<Subscription> expiring =
                subscriptionRepository.findTrialsExpiringSoon(now, sevenDay);

        log.info("📧 [Scheduler] {} trial(s) expiring in ≤ 7 days", expiring.size());

        for (Subscription sub : expiring) {
            log.info("📧 Trial expiry warning → userId={} daysLeft={}",
                    sub.getUserId(), sub.daysRemaining());
            // emailService.sendTrialExpiryWarning(sub.getUserId(), sub.daysRemaining());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Daily 10:30 AM — Remind paid users with ≤ 3 days left to renew
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 30 10 * * *")
    public void sendRenewalReminders() {
        LocalDateTime now      = LocalDateTime.now();
        LocalDateTime threeDays = now.plusDays(3);

        List<Subscription> expiring =
                subscriptionRepository.findActiveExpiringSoon(now, threeDays);

        log.info("📧 [Scheduler] {} paid subscription(s) expiring in ≤ 3 days", expiring.size());

        for (Subscription sub : expiring) {
            log.info("📧 Renewal reminder → userId={} plan={} daysLeft={}",
                    sub.getUserId(), sub.getPlanDuration().getDisplayName(), sub.daysRemaining());
            // emailService.sendRenewalReminder(sub.getUserId(), sub.getPlanDuration(), sub.daysRemaining());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Every hour — Clean up PENDING records older than 1 hour
    // Razorpay orders expire in 15 min; this prevents orphaned PENDING rows
    // when a user opens payment modal but never completes it
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void cleanupStalePendingSubscriptions() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);
        int deleted = subscriptionRepository.deleteStalePendingSubscriptions(cutoff);
        if (deleted > 0) {
            log.info("🧹 [Scheduler] Cleaned up {} stale PENDING subscription(s)", deleted);
        }
    }
}