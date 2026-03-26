package in.BidPilots.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class BidDTO {
    private Long id;
    private String bidNumber;
    private String raNumber;
    private String bidDocumentUrl;
    private String raDocumentUrl;
    private LocalDateTime bidStartDate;
    private LocalDateTime bidEndDate;
    private LocalDateTime raStartDate;
    private LocalDateTime raEndDate;
    private String items;
    private String dataContent;
    private String quantity;
    private String department;
    private String ministry;
    private String bidType;
    private Boolean isActive;
    private Boolean isDeactive;
    private String state;
    private Long stateId;
    private String city;
    private Long cityId;
    
    public static BidDTO fromBid(in.BidPilots.entity.Bid bid) {
        BidDTO dto = new BidDTO();
        dto.setId(bid.getId());
        dto.setBidNumber(bid.getBidNumber());
        dto.setRaNumber(bid.getRaNumber());
        dto.setBidDocumentUrl(bid.getBidDocumentUrl());
        dto.setRaDocumentUrl(bid.getRaDocumentUrl());
        dto.setBidStartDate(bid.getBidStartDate());
        dto.setBidEndDate(bid.getBidEndDate());
        dto.setRaStartDate(bid.getRaStartDate());
        dto.setRaEndDate(bid.getRaEndDate());
        dto.setItems(bid.getItems());
        dto.setDataContent(bid.getDataContent());
        dto.setQuantity(bid.getQuantity());
        dto.setDepartment(bid.getDepartment());
        dto.setMinistry(bid.getMinistry());
        dto.setBidType(bid.getBidType());
        dto.setIsActive(bid.getIsActive());
        dto.setIsDeactive(bid.getIsDeactive());
        
        if (bid.getState() != null) {
            dto.setState(bid.getState().getStateName());
            dto.setStateId(bid.getState().getId());
        }
        
        if (bid.getConsigneeCity() != null) {
            dto.setCity(bid.getConsigneeCity().getCityName());
            dto.setCityId(bid.getConsigneeCity().getId());
        }
        
        return dto;
    }
}