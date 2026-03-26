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
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "subscriptions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // FK to users.id — no @ManyToOne to avoid lazy-loading issues
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_duration", nullable = false)
    private PlanDuration planDuration;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SubscriptionStatus status;

    @Column(name = "start_date", nullable = false)
    private LocalDateTime startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDateTime endDate;

    // Razorpay fields
    @Column(name = "razorpay_order_id")
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id")
    private String razorpayPaymentId;

    @Column(name = "razorpay_signature")
    private String razorpaySignature;

    // Stored in PAISE — divide by 100 to show rupees to user
    @Column(name = "amount_paid_paise")
    private Integer amountPaidPaise;

    @Column(name = "last_payment_date")
    private LocalDateTime lastPaymentDate;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── Helper methods ──

    public boolean isCurrentlyActive() {
        return (status == SubscriptionStatus.TRIAL || status == SubscriptionStatus.ACTIVE)
                && endDate != null
                && endDate.isAfter(LocalDateTime.now());
    }

    public long daysRemaining() {
        if (endDate == null) return 0;
        return Math.max(0, ChronoUnit.DAYS.between(LocalDateTime.now(), endDate));
    }

    public boolean isInTrialPeriod() {
        return status == SubscriptionStatus.TRIAL && isCurrentlyActive();
    }

    public int getAmountPaidRupees() {
        return amountPaidPaise != null ? amountPaidPaise / 100 : 0;
    }
}