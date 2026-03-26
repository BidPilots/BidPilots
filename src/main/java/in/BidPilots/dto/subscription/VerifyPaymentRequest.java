package in.BidPilots.dto.subscription;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * POST /api/subscriptions/verify-payment
 * Sent by frontend AFTER Razorpay checkout modal completes successfully.
 */
@Data
public class VerifyPaymentRequest {

    @NotBlank(message = "Order ID is required")
    private String razorpayOrderId;

    @NotBlank(message = "Payment ID is required")
    private String razorpayPaymentId;

    @NotBlank(message = "Signature is required")
    private String razorpaySignature;
}