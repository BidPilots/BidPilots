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
    @Index(name = "idx_usf_user_id", columnList = "user_id"),
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

    @Column(name = "filter_type", nullable = false, length = 20)
    private String filterType;

    // Store multiple state IDs as JSON array
    @Column(name = "state_ids", columnDefinition = "TEXT")
    private String stateIds;

    // Store multiple city IDs as JSON array
    @Column(name = "city_ids", columnDefinition = "TEXT")
    private String cityIds;

    @Column(name = "category_id")
    private Long categoryId;

    // FIX: Removed conflicting @PrePersist/@PreUpdate — @CreatedDate/@LastModifiedDate
    // from AuditingEntityListener handles this. Having both caused double-writes.
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;
}