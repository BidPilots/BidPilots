package in.BidPilots.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_saved_filters", indexes = {
    @Index(name = "idx_usf_user_id",   columnList = "user_id"),
    @Index(name = "idx_usf_user_type", columnList = "user_id, filter_type")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class UserSavedFilter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "filter_name", nullable = false, length = 100)
    private String filterName;

    /**
     * Filter types:
     *  SMART      — category + state/city, fuzzy matching (default)
     *  EXACT      — category + state/city, exact phrase match
     *  BROAD      — category + state/city, any token match
     *  MINISTRY   — ministry + department + keywords
     *  BOQ        — BOQ title search inside bid_details.pdf_content
     *  CONSIGNEE  — department + ministry + state + city (consignee-based)
     *  LOCATION   — state + city only (all bids in location)
     */
    @Column(name = "filter_type", nullable = false, length = 20)
    private String filterType;

    // Store multiple state IDs as JSON array: "[1,2,3]"
    @Column(name = "state_ids", columnDefinition = "TEXT")
    private String stateIds;

    // Store multiple city IDs as JSON array: "[10,11]"
    @Column(name = "city_ids", columnDefinition = "TEXT")
    private String cityIds;

    // Used by SMART / EXACT / BROAD filter types
    @Column(name = "category_id")
    private Long categoryId;

    // ── Ministry / Consignee filter fields ────────────────────────────────────
    // Pipe-separated to hold multiple values without a join table.
    // e.g. ministry = "Ministry of Health||Ministry of Defence"
    @Column(name = "ministry", columnDefinition = "TEXT")
    private String ministry;

    @Column(name = "department", columnDefinition = "TEXT")
    private String department;

    // Comma-separated item keywords for further narrowing (MINISTRY filter only)
    @Column(name = "keywords", columnDefinition = "TEXT")
    private String keywords;

    // ── BOQ filter field ──────────────────────────────────────────────────────
    // The BOQ title/keyword to search for inside bid_details.pdf_content.
    // Only used when filterType = "BOQ".
    @Column(name = "boq_title", columnDefinition = "TEXT")
    private String boqTitle;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;
}