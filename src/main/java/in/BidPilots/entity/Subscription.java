package in.BidPilots.entity;

import in.BidPilots.enums.PlanDuration;
import in.BidPilots.enums.SubscriptionStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "subscriptions",
       indexes = {
           @Index(name = "idx_sub_user_id",        columnList = "user_id"),
           @Index(name = "idx_sub_status",          columnList = "status"),
           @Index(name = "idx_sub_end_date",        columnList = "end_date"),
           @Index(name = "idx_sub_razorpay_order",  columnList = "razorpay_order_id")
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_duration", nullable = false)
    private PlanDuration planDuration;

    /**
     * The plan the user is currently paying for but whose payment isn't yet verified.
     * Set during create-order, cleared on verify-payment.
     * This ensures the correct plan is applied even if the user had a different active plan.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "pending_plan_duration")
    private PlanDuration pendingPlanDuration;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SubscriptionStatus status;

    @Column(name = "start_date", nullable = false)
    private LocalDateTime startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDateTime endDate;

    // ── Razorpay fields ──────────────────────────────────────────────────────

    @Column(name = "razorpay_order_id", length = 100)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id", length = 100)
    private String razorpayPaymentId;

    @Column(name = "razorpay_signature", length = 256)
    private String razorpaySignature;

    @Column(name = "amount_paid_paise")
    private Integer amountPaidPaise;

    @Column(name = "last_payment_date")
    private LocalDateTime lastPaymentDate;

    // ── Audit ────────────────────────────────────────────────────────────────

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── Helper methods ───────────────────────────────────────────────────────

    /**
     * True if status is TRIAL or ACTIVE and the end date is still in the future.
     * PENDING, EXPIRED, CANCELLED all return false.
     */
    public boolean isCurrentlyActive() {
        return (status == SubscriptionStatus.TRIAL || status == SubscriptionStatus.ACTIVE)
                && endDate != null
                && endDate.isAfter(LocalDateTime.now());
    }

    public long daysRemaining() {
        if (endDate == null) return 0;
        return Math.max(0, ChronoUnit.DAYS.between(LocalDateTime.now(), endDate));
    }

    public long hoursRemaining() {
        if (endDate == null) return 0;
        return Math.max(0, ChronoUnit.HOURS.between(LocalDateTime.now(), endDate));
    }

    public String getFormattedEndDate() {
        if (endDate == null) return "N/A";
        return endDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a"));
    }

    public String getTimeRemaining() {
        long days = daysRemaining();
        if (days > 30) {
            return String.format("%d months", days / 30);
        } else if (days > 0) {
            return String.format("%d days", days);
        } else {
            long hours = hoursRemaining();
            return hours > 0 ? String.format("%d hours", hours) : "Expired";
        }
    }

    public boolean isInTrialPeriod() {
        return status == SubscriptionStatus.TRIAL && isCurrentlyActive();
    }

    public int getAmountPaidRupees() {
        return amountPaidPaise != null ? amountPaidPaise / 100 : 0;
    }

    /**
     * Returns true if this subscription was renewed/extended at least once.
     * A MONTHLY user on 60 days remaining has been extended.
     */
    public boolean wasExtended() {
        if (planDuration == null || !isCurrentlyActive()) return false;
        return daysRemaining() > planDuration.getDurationDays();
    }
}