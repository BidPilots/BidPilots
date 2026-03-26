package in.BidPilots.dto.subscription;

import in.BidPilots.enums.PlanDuration;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * POST /api/subscriptions/create-order
 * Body: { "planDuration": "MONTHLY" }
 */
@Data
public class CreateOrderRequest {

    @NotNull(message = "Plan duration is required")
    private PlanDuration planDuration;
}