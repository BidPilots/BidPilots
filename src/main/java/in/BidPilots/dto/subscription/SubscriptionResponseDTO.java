package in.BidPilots.dto.subscription;

import in.BidPilots.entity.Subscription;
import in.BidPilots.enums.PlanDuration;
import in.BidPilots.enums.SubscriptionStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SubscriptionResponseDTO {

    private Long             id;
    private Long             userId;
    private PlanDuration     planDuration;
    private String           planDisplayName;
    private SubscriptionStatus status;
    private LocalDateTime    startDate;
    private LocalDateTime    endDate;
    private long             daysRemaining;
    private boolean          active;
    private boolean          inTrial;
    private int              amountPaidRupees;
    private LocalDateTime    lastPaymentDate;

    public static SubscriptionResponseDTO from(Subscription s) {
        SubscriptionResponseDTO dto = new SubscriptionResponseDTO();
        dto.setId(s.getId());
        dto.setUserId(s.getUserId());
        dto.setPlanDuration(s.getPlanDuration());
        dto.setPlanDisplayName(s.getPlanDuration().getDisplayName());
        dto.setStatus(s.getStatus());
        dto.setStartDate(s.getStartDate());
        dto.setEndDate(s.getEndDate());
        dto.setDaysRemaining(s.daysRemaining());
        dto.setActive(s.isCurrentlyActive());
        dto.setInTrial(s.isInTrialPeriod());
        dto.setAmountPaidRupees(s.getAmountPaidRupees());
        dto.setLastPaymentDate(s.getLastPaymentDate());
        return dto;
    }
}