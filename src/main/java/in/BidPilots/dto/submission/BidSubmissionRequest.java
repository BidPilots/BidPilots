package in.BidPilots.dto.submission;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

/**
 * Request body sent by the frontend when a user saves or submits a bid price.
 * Used by POST /api/user/submissions.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BidSubmissionRequest {

    /** The GeM Bid ID (from the matched bid card) */
    @NotNull(message = "Bid ID is required")
    private Long bidId;

    /** GeM bid number — stored denormalised for display (e.g. GEM/2024/B/1234567) */
    @Size(max = 100)
    private String bidNumber;

    /** Short item description — stored for display in submissions list */
    private String bidItems;

    /** Price per unit in INR — must be > 0 */
    @NotNull(message = "Quoted price is required")
    @DecimalMin(value = "0.01", message = "Price must be greater than zero")
    @Digits(integer = 12, fraction = 2, message = "Invalid price format — max 12 digits, 2 decimal places")
    private BigDecimal quotedPrice;

    /** Total price (frontend calculates quotedPrice × quantity and sends here) */
    @Digits(integer = 14, fraction = 2)
    private BigDecimal totalPrice;

    /** Quantity the user intends to supply */
    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity;

    /** Optional free-text notes / remarks */
    @Size(max = 2000, message = "Notes must be under 2000 characters")
    private String notes;

    /**
     * "DRAFT"     → save without finalising (user can edit/submit later)
     * "SUBMITTED" → finalise and submit the price quote
     */
    @Pattern(regexp = "DRAFT|SUBMITTED", message = "Status must be DRAFT or SUBMITTED")
    private String status = "DRAFT";
}