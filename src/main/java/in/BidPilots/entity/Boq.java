package in.BidPilots.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "boq",
    indexes = {
        @Index(name = "idx_boq_title", columnList = "boq_title"),
        @Index(name = "idx_boq_gem_id", columnList = "gem_boq_id"),
        @Index(name = "idx_boq_active", columnList = "is_active"),
        @Index(name = "idx_boq_created", columnList = "created_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Boq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "boq_title", nullable = false, length = 500)
    private String boqTitle;

    @Column(name = "gem_boq_id", length = 100)
    private String gemBoqId;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}