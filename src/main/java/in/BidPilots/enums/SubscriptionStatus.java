package in.BidPilots.enums;

/**
 * Lifecycle states for a subscription record.
 *
 * PENDING    — Razorpay order created but payment not yet verified.
 *              Acts as a placeholder so the orderId can be stored before payment.
 * TRIAL      — Free 30-day trial, auto-created on registration.
 * ACTIVE     — Paid and within valid date range.
 * EXPIRED    — endDate passed, no access. Marked by SubscriptionScheduler daily.
 * CANCELLED  — Manually cancelled; access continues until endDate, then EXPIRED.
 */
public enum SubscriptionStatus {
    PENDING,
    TRIAL,
    ACTIVE,
    EXPIRED,
    CANCELLED
}