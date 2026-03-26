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
        @Index(name = "idx_mb_user_id", columnList = "user_id"),
        @Index(name = "idx_mb_user_viewed", columnList = "user_id, is_viewed"),
        @Index(name = "idx_mb_bid_id", columnList = "bid_id")
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

    @CreatedDate
    @Column(name = "matched_at", nullable = false, updatable = false)
    private LocalDateTime matchedAt;

    // FIX: Removed conflicting @PrePersist — @CreatedDate from AuditingEntityListener
    // handles matchedAt. The old @PrePersist fought with the auditing listener.
}