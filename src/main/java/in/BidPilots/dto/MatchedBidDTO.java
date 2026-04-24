package in.BidPilots.dto;

import in.BidPilots.entity.Bid;
import in.BidPilots.entity.MatchedBids;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MatchedBidDTO {
    private Long id;
    private Long userId;
    private Long bidId;
    private Long filterId;
    private Long categoryId;
    private Boolean isViewed;
    private Boolean isDeleted;      // NEW: true when the user has dismissed this bid
    private LocalDateTime matchedAt;
    private LocalDateTime deletedAt; // NEW: timestamp of dismissal, null if not dismissed

    // Bid details
    private String bidNumber;
    private String raNumber;
    private String items;
    private String quantity;
    private String department;
    private String ministry;
    private LocalDateTime bidStartDate;
    private LocalDateTime bidEndDate;
    private String bidType;
    private String state;
    private String city;
    private Boolean isActive;
    private Boolean isFinalized;
    private String bidDocumentUrl;

    // Pre-bid date time from BidDetails
    private LocalDateTime preBidDateTime;

    public static MatchedBidDTO fromEntity(MatchedBids match, Bid bid, LocalDateTime preBidDateTime) {
        MatchedBidDTO dto = new MatchedBidDTO();
        dto.setId(match.getId());
        dto.setUserId(match.getUserId());
        dto.setBidId(match.getBidId());
        dto.setFilterId(match.getFilterId());
        dto.setCategoryId(match.getCategoryId());
        dto.setIsViewed(match.getIsViewed());
        dto.setIsDeleted(match.getIsDeleted());       // NEW
        dto.setDeletedAt(match.getDeletedAt());       // NEW
        dto.setMatchedAt(match.getMatchedAt());
        dto.setPreBidDateTime(preBidDateTime);

        if (bid != null) {
            dto.setBidNumber(bid.getBidNumber());
            dto.setRaNumber(bid.getRaNumber());
            dto.setItems(bid.getItems());
            dto.setQuantity(bid.getQuantity());
            dto.setDepartment(bid.getDepartment());
            dto.setMinistry(bid.getMinistry());
            dto.setBidStartDate(bid.getBidStartDate());
            dto.setBidEndDate(bid.getBidEndDate());
            dto.setBidType(bid.getBidType());
            dto.setState(bid.getState() != null ? bid.getState().getStateName() : null);
            dto.setCity(bid.getConsigneeCity() != null ? bid.getConsigneeCity().getCityName() : null);
            dto.setIsActive(bid.getIsActive());
            dto.setIsFinalized(bid.getIsFinalized());
            dto.setBidDocumentUrl(bid.getBidDocumentUrl());
        }

        return dto;
    }

    // Overloaded method for backward compatibility
    public static MatchedBidDTO fromEntity(MatchedBids match, Bid bid) {
        return fromEntity(match, bid, null);
    }
}