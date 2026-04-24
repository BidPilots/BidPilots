package in.BidPilots.repository;

import in.BidPilots.entity.Subscription;
import in.BidPilots.enums.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByUserId(Long userId);

    Optional<Subscription> findByRazorpayOrderId(String orderId);

    boolean existsByUserId(Long userId);

    // ── Scheduler queries ────────────────────────────────────────────────────

    /** TRIAL/ACTIVE whose end date has already passed — needs marking EXPIRED */
    @Query("SELECT s FROM Subscription s WHERE s.status IN ('TRIAL', 'ACTIVE') " +
           "AND s.endDate < :now")
    List<Subscription> findExpiredButNotMarked(@Param("now") LocalDateTime now);

    /** Trials expiring within N days — for warning emails */
    @Query("SELECT s FROM Subscription s WHERE s.status = 'TRIAL' " +
           "AND s.endDate BETWEEN :now AND :cutoff")
    List<Subscription> findTrialsExpiringSoon(
            @Param("now") LocalDateTime now,
            @Param("cutoff") LocalDateTime cutoff);

    /** Paid subs expiring within N days — for renewal reminders */
    @Query("SELECT s FROM Subscription s WHERE s.status = 'ACTIVE' " +
           "AND s.endDate BETWEEN :now AND :cutoff")
    List<Subscription> findActiveExpiringSoon(
            @Param("now") LocalDateTime now,
            @Param("cutoff") LocalDateTime cutoff);

    // ── Admin queries ────────────────────────────────────────────────────────

    /** Count of all subscriptions by status — for admin dashboard */
    @Query("SELECT s.status, COUNT(s) FROM Subscription s GROUP BY s.status")
    List<Object[]> countByStatus();

    /** All ACTIVE or TRIAL subscriptions */
    @Query("SELECT s FROM Subscription s WHERE s.status IN ('ACTIVE', 'TRIAL') " +
           "AND s.endDate > :now")
    List<Subscription> findAllCurrentlyActive(@Param("now") LocalDateTime now);

    /**
     * Cleanup stale PENDING records older than 1 hour.
     * Razorpay order IDs expire in 15 minutes; 1 hour is a safe buffer.
     */
    @Modifying
    @Query("DELETE FROM Subscription s WHERE s.status = 'PENDING' " +
           "AND s.createdAt < :cutoff")
    int deleteStalePendingSubscriptions(@Param("cutoff") LocalDateTime cutoff);
}