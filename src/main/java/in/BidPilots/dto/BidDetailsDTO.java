package in.BidPilots.dto;

import in.BidPilots.entity.BidDetails;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class BidDetailsDTO {
	private Long id;
	private Long bidId;
	private String bidNumber;
	private String pdfContent;
	private String extractionStatus;
	private LocalDateTime preBidDateTime;

	public static BidDetailsDTO fromBidDetails(BidDetails bidDetails) {
		BidDetailsDTO dto = new BidDetailsDTO();
		dto.setId(bidDetails.getId());
		dto.setBidId(bidDetails.getBid().getId());
		dto.setBidNumber(bidDetails.getBid().getBidNumber());
		dto.setPdfContent(bidDetails.getPdfContent());
		dto.setExtractionStatus(bidDetails.getExtractionStatus());
		dto.setPreBidDateTime(bidDetails.getPreBidDateTime());

		return dto;
	}
}