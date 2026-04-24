package in.BidPilots.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "matched_bids",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "bid_id"}, name = "uk_user_bid"),
    indexes = {
        @Index(name = "idx_mb_user_id",      columnList = "user_id"),
        @Index(name = "idx_mb_user_viewed",   columnList = "user_id, is_viewed"),
        @Index(name = "idx_mb_bid_id",        columnList = "bid_id"),
        // NEW: speeds up "skip deleted" filter during the 15-min scheduling run
        @Index(name = "idx_mb_user_deleted",  columnList = "user_id, is_deleted")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class MatchedBids {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "bid_id", nullable = false)
    private Long bidId;

    @Column(name = "filter_id")
    private Long filterId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "is_viewed", nullable = false)
    private Boolean isViewed = false;

    /**
     * Soft-delete flag — set to TRUE when the user explicitly dismisses a bid
     * from their matched-bids list.
     *
     * Semantics:
     *  • The row is NEVER physically removed, so the unique constraint on
     *    (user_id, bid_id) prevents the same bid from being re-inserted by the
     *    15-minute scheduling job (existsByUserIdAndBidId still returns true).
     *  • The controller and service always filter with isDeleted = false so
     *    dismissed bids are invisible to the user.
     *  • The scheduler's "already matched" check (existsByUserIdAndBidId) works
     *    on ALL rows regardless of isDeleted, which is exactly what we want:
     *    once dismissed, the bid never comes back.
     *
     * DB migration:
     *   ALTER TABLE matched_bids ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
     *   CREATE INDEX idx_mb_user_deleted ON matched_bids(user_id, is_deleted);
     */
    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;

    /**
     * Timestamp of when the user dismissed/deleted this match.
     * NULL when isDeleted = false.
     *
     * DB migration:
     *   ALTER TABLE matched_bids ADD COLUMN deleted_at DATETIME NULL;
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreatedDate
    @Column(name = "matched_at", nullable = false, updatable = false)
    private LocalDateTime matchedAt;

    // FIX: Removed conflicting @PrePersist — @CreatedDate from AuditingEntityListener
    // handles matchedAt. The old @PrePersist fought with the auditing listener.
}