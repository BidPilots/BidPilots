package in.BidPilots.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;

@Entity
@Table(name = "bids", uniqueConstraints = { @UniqueConstraint(columnNames = "bid_number", name = "uk_bid_number"),
		@UniqueConstraint(columnNames = "ra_number", name = "uk_ra_number") })
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({ "hibernateLazyInitializer", "handler" })
public class Bid {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// Bid/RA Numbers
	@Column(name = "bid_number", unique = true, nullable = false)
	private String bidNumber;

	@Column(name = "ra_number", unique = true)
	private String raNumber;

	// Document URLs
	@Column(name = "bid_document_url", length = 500)
	private String bidDocumentUrl;

	@Column(name = "ra_document_url", length = 500)
	private String raDocumentUrl;

	// Dates
	@Column(name = "bid_start_date")
	private LocalDateTime bidStartDate;

	@Column(name = "bid_end_date")
	private LocalDateTime bidEndDate;

	@Column(name = "ra_start_date")
	private LocalDateTime raStartDate;

	@Column(name = "ra_end_date")
	private LocalDateTime raEndDate;

	@Column(name = "data_content", length = 1000)
	private String dataContent;

	@Column(name = "items", length = 1000)
	private String items;

	@Column(name = "quantity")
	private String quantity;

	@Column(name = "department", length = 500)
	private String department;

	@Column(name = "ministry", length = 500)
	private String ministry;

	@Column(name = "bid_type")
	private String bidType;

	@Column(name = "is_active")
	private Boolean isActive;

	@Column(name = "is_deactive")
	private Boolean isDeactive;

	@Column(name = "is_finalized")
	private Boolean isFinalized = false;

	@Column(name = "created_date")
	private LocalDateTime createdDate;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name = "state_id")
	@JsonIgnoreProperties({ "cities", "bids" })
	private State state;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name = "city_id")
	@JsonIgnoreProperties({ "state", "bids" })
	private City consigneeCity;

	// Helper method to get full bid document URL
	public String getFullBidDocumentUrl() {
		if (bidDocumentUrl != null && !bidDocumentUrl.startsWith("http")) {
			return "https://bidplus.gem.gov.in/" + bidDocumentUrl;
		}
		return bidDocumentUrl;
	}

	// Helper method to get full RA document URL
	public String getFullRaDocumentUrl() {
		if (raDocumentUrl != null && !raDocumentUrl.startsWith("http")) {
			return "https://bidplus.gem.gov.in" + raDocumentUrl;
		}
		return raDocumentUrl;
	}

	// Helper method to determine bid status
	public String getCurrentStatus() {
		if (bidEndDate == null)
			return "Unknown";
		return bidEndDate.isAfter(LocalDateTime.now()) ? "Active" : "Closed";
	}

	// Helper method to get state name safely
	public String getStateName() {
		return state != null ? state.getStateName() : null;
	}

	// Helper method to get city name safely
	public String getCityName() {
		return consigneeCity != null ? consigneeCity.getCityName() : null;
	}

	@PrePersist
	protected void onCreate() {
		if (createdDate == null) {
			createdDate = LocalDateTime.now();
		}
		if (isFinalized == null) {
			isFinalized = false;
		}
	}
}