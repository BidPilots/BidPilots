package in.BidPilots.enums;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Single source of truth for all subscription plan metadata.
 *
 * File location:
 *   /BidPilots/src/main/java/in/BidPilots/enums/PlanDuration.java
 */
public enum PlanDuration {

    //                priceRupees  days   displayName
    TRIAL       (0,          30,  "Free Trial"),
    MONTHLY     (199,        30,  "1 Month"),
    QUARTERLY   (499,        90,  "3 Months"),
    SEVEN_MONTH (1199,       210, "7 Months"),
    ANNUAL      (1999,       365, "12 Months");

    private final int    priceRupees;
    private final int    durationDays;
    private final String displayName;

    PlanDuration(int priceRupees, int durationDays, String displayName) {
        this.priceRupees  = priceRupees;
        this.durationDays = durationDays;
        this.displayName  = displayName;
    }

    // ── Primitive accessors ───────────────────────────────────────────────────

    public int    getPriceRupees()  { return priceRupees; }
    public int    getPricePaise()   { return priceRupees * 100; }
    public int    getDurationDays() { return durationDays; }
    public String getDisplayName()  { return displayName; }
    public boolean isFree()         { return priceRupees == 0; }

    // ── BigDecimal / Razorpay accessors ──────────────────────────────────────

    /** Price as BigDecimal e.g. 199.00 — for display and persistence */
    public BigDecimal getPrice() {
        return new BigDecimal(priceRupees).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Amount in paise as long — required by Razorpay Orders API */
    public long getAmountInPaise() {
        return (long) priceRupees * 100;
    }

    /** Price per day rounded to 1 decimal. Returns ZERO for free plans. */
    public BigDecimal getPricePerDay() {
        if (durationDays == 0 || priceRupees == 0) return BigDecimal.ZERO;
        return getPrice().divide(new BigDecimal(durationDays), 1, RoundingMode.HALF_UP);
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /** Case-insensitive lookup — fromPlanType("monthly") → MONTHLY */
    public static PlanDuration fromPlanType(String planType) {
        if (planType == null || planType.isBlank())
            throw new IllegalArgumentException("Plan type must not be blank");
        for (PlanDuration p : values())
            if (p.name().equalsIgnoreCase(planType.trim())) return p;
        throw new IllegalArgumentException("Unknown plan type: \"" + planType + "\"");
    }
}