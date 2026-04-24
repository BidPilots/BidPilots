package in.BidPilots.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SaveFilterRequest {

    // userId is resolved from the JWT in the controller — never from request body (IDOR prevention).

    @NotBlank(message = "Filter name is required")
    @Size(max = 100, message = "Filter name cannot exceed 100 characters")
    private String filterName;

    /**
     * Filter types:
     *  SMART    — category + state/city, fuzzy matching
     *  EXACT    — category + state/city, exact phrase match
     *  BROAD    — category + state/city, any token match
     *  BOQ      — BOQ title search inside bid_details.pdf_content
     *  LOCATION — state + city only (no category required)
     */
    @Size(max = 20, message = "Filter type cannot exceed 20 characters")
    private String filterType;  // SMART | EXACT | BROAD | BOQ | LOCATION

    // ── Location fields ───────────────────────────────────────────────────────
    private List<Long> stateIds;
    private List<Long> cityIds;

    // ── Category filter fields ────────────────────────────────────────────────
    // Required for SMART / EXACT / BROAD. Not used for BOQ / LOCATION.
    private Long categoryId;

    // ── BOQ filter field ──────────────────────────────────────────────────────
    // The BOQ title/keyword to search for inside bid_details.pdf_content.
    // Required when filterType = "BOQ".
    private String boqTitle;
}