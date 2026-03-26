package in.BidPilots.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Stores a user's bid price submission for a GeM bid.
 *
 * Completely independent of all existing entities.
 * One user can submit only one price per bid (unique constraint on user_id + bid_id).
 * The user can update or delete their submission at any time before the bid closes.
 *
 * status values:
 *   DRAFT     — saved but not yet submitted
 *   SUBMITTED — submitted / finalised
 *   WITHDRAWN — user deleted/withdrew the submission
 */
@Entity
@Table(
    name = "user_bid_submissions",
    uniqueConstraints = @UniqueConstraint(
        columnNames = {"user_id", "bid_id"},
        name = "uk_ubs_user_bid"
    ),
    indexes = {
        @Index(name = "idx_ubs_user_id",   columnList = "user_id"),
        @Index(name = "idx_ubs_bid_id",    columnList = "bid_id"),
        @Index(name = "idx_ubs_status",    columnList = "status"),
        @Index(name = "idx_ubs_user_status", columnList = "user_id, status")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class UserBidSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to the user who submitted */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * FK to the GeM Bid row (the existing Bid entity).
     * Stored as a plain Long — no @ManyToOne — so this entity has
     * zero coupling to the existing Bid entity.
     */
    @Column(name = "bid_id", nullable = false)
    private Long bidId;

    /**
     * The bid number shown on GeM portal (e.g. GEM/2024/B/1234567).
     * Denormalised here for display without joining the Bid table.
     */
    @Column(name = "bid_number", length = 100)
    private String bidNumber;

    /** Short description of what the bid is for — shown in the submissions list */
    @Column(name = "bid_items", columnDefinition = "TEXT")
    private String bidItems;

    // ── Price fields ────────────────────────────────────────────────────────

    /** The unit price the user wants to quote (in INR) */
    @Column(name = "quoted_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal quotedPrice;

    /** Total price = quotedPrice × quantity (user can override) */
    @Column(name = "total_price", precision = 16, scale = 2)
    private BigDecimal totalPrice;

    /** Quantity the user intends to supply */
    @Column(name = "quantity")
    private Integer quantity;

    /** Optional notes / remarks from the user */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ── Status ──────────────────────────────────────────────────────────────

    @Column(name = "status", nullable = false, length = 20)
    private String status = "DRAFT"; // DRAFT | SUBMITTED | WITHDRAWN

    // ── Timestamps ──────────────────────────────────────────────────────────

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** When the user actually hit "Submit" (changed status to SUBMITTED) */
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;
}