package in.BidPilots.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;

@Entity
@Table(name = "bid_details")
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({ "hibernateLazyInitializer", "handler" })
public class BidDetails {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "bid_id", nullable = false, unique = true)
	@JsonIgnoreProperties({ "hibernateLazyInitializer", "handler" })
	private Bid bid;

	@Column(name = "pdf_content", columnDefinition = "LONGTEXT")
	private String pdfContent;

	@Column(name = "extraction_status")
	private String extractionStatus = "PENDING"; // PENDING, PROCESSING, COMPLETED, FAILED

	@Column(name = "pre_bid_date_time")
	private LocalDateTime preBidDateTime;

}