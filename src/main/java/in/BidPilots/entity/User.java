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
@Table(name = "users",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = "email",         name = "uk_user_email"),
        @UniqueConstraint(columnNames = "mobile_number", name = "uk_user_mobile"),
        @UniqueConstraint(columnNames = "gst_number",    name = "uk_user_gst")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
@JsonIgnoreProperties({ "hibernateLazyInitializer", "handler", "password" })
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 255)
    private String companyName;

    @Column(name = "email", nullable = false, length = 100, unique = true)
    private String email;

    @Column(name = "mobile_number", nullable = false, length = 15, unique = true)
    private String mobileNumber;

    @Column(name = "gst_number", nullable = false, length = 15, unique = true)
    private String gstNumber;

    @Column(name = "password", nullable = false, length = 255)
    @JsonIgnoreProperties
    private String password;

    @Column(name = "is_active")
    private Boolean isActive = false;

    @Column(name = "is_email_verified")
    private Boolean isEmailVerified = false;

    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    @Column(name = "registration_completed")
    private Boolean registrationCompleted = false;

    // ADMIN or USER
    @Column(name = "role", length = 20)
    private String role = "USER";

    // ── Login tracking ──────────────────────────────────────────
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "login_count")
    private Integer loginCount = 0;

    @Column(name = "failed_attempts")
    private Integer failedAttempts = 0;

    @Column(name = "lock_time")
    private LocalDateTime lockTime;

    // ── Auditing ────────────────────────────────────────────────
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── Lifecycle hooks ─────────────────────────────────────────
    @PrePersist
    protected void onCreate() {
        createdAt        = LocalDateTime.now();
        updatedAt        = LocalDateTime.now();
        if (isActive            == null) isActive            = false;
        if (isEmailVerified     == null) isEmailVerified     = false;
        if (registrationCompleted == null) registrationCompleted = false;
        if (loginCount          == null) loginCount          = 0;
        if (failedAttempts      == null) failedAttempts      = 0;
        if (role                == null) role                = "USER";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Helpers ─────────────────────────────────────────────────
    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(this.role);
    }
}