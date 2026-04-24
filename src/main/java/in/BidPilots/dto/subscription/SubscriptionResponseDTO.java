package in.BidPilots.dto.subscription;

import in.BidPilots.entity.Subscription;
import in.BidPilots.enums.PlanDuration;
import in.BidPilots.enums.SubscriptionStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SubscriptionResponseDTO {

    private Long id;
    private Long userId;
    private PlanDuration planDuration;
    private String planDisplayName;
    private SubscriptionStatus status;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private String formattedEndDate;
    private long daysRemaining;
    private String timeRemaining;
    private boolean active;
    private boolean inTrial;
    private boolean isPending;
    private int amountPaidRupees;
    private LocalDateTime lastPaymentDate;

    /**
     * True when the user paid again while still having days left —
     * meaning endDate was extended beyond the current plan's normal duration.
     */
    private boolean isExtended;

    public static SubscriptionResponseDTO from(Subscription s) {
        SubscriptionResponseDTO dto = new SubscriptionResponseDTO();
        dto.setId(s.getId());
        dto.setUserId(s.getUserId());
        dto.setPlanDuration(s.getPlanDuration());
        dto.setPlanDisplayName(s.getPlanDuration() != null ? s.getPlanDuration().getDisplayName() : "—");
        dto.setStatus(s.getStatus());
        dto.setStartDate(s.getStartDate());
        dto.setEndDate(s.getEndDate());
        dto.setFormattedEndDate(s.getFormattedEndDate());
        dto.setDaysRemaining(s.daysRemaining());
        dto.setTimeRemaining(s.getTimeRemaining());
        dto.setActive(s.isCurrentlyActive());
        dto.setInTrial(s.isInTrialPeriod());
        dto.setPending(s.getStatus() == in.BidPilots.enums.SubscriptionStatus.PENDING);
        dto.setAmountPaidRupees(s.getAmountPaidRupees());
        dto.setLastPaymentDate(s.getLastPaymentDate());
        dto.setExtended(s.wasExtended());
        return dto;
    }
}