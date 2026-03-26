package in.BidPilots.dto.submission;

import in.BidPilots.entity.UserBidSubmission;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response body returned to the frontend after save, update, or fetch.
 * Contains everything the frontend needs to display the submission card.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BidSubmissionResponse {

    private Long   id;
    private Long   userId;
    private Long   bidId;
    private String bidNumber;
    private String bidItems;

    private BigDecimal quotedPrice;
    private BigDecimal totalPrice;
    private Integer    quantity;
    private String     notes;
    private String     status;         // DRAFT | SUBMITTED | WITHDRAWN

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime submittedAt;

    /** Build from entity — used by the service layer */
    public static BidSubmissionResponse from(UserBidSubmission s) {
        BidSubmissionResponse r = new BidSubmissionResponse();
        r.setId(s.getId());
        r.setUserId(s.getUserId());
        r.setBidId(s.getBidId());
        r.setBidNumber(s.getBidNumber());
        r.setBidItems(s.getBidItems());
        r.setQuotedPrice(s.getQuotedPrice());
        r.setTotalPrice(s.getTotalPrice());
        r.setQuantity(s.getQuantity());
        r.setNotes(s.getNotes());
        r.setStatus(s.getStatus());
        r.setCreatedAt(s.getCreatedAt());
        r.setUpdatedAt(s.getUpdatedAt());
        r.setSubmittedAt(s.getSubmittedAt());
        return r;
    }
}