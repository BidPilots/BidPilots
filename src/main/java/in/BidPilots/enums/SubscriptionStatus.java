package in.BidPilots.enums;

public enum SubscriptionStatus {
    TRIAL,      // First 30 days free — auto-created on registration
    ACTIVE,     // Paid and within valid date range
    EXPIRED,    // End date passed, no access
    CANCELLED   // Manually cancelled, access until end date
}