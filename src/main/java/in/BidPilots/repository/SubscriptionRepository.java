package in.BidPilots.repository;

import in.BidPilots.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
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

    // Trials expiring within N days — for warning emails
    @Query("SELECT s FROM Subscription s WHERE s.status = 'TRIAL' " +
           "AND s.endDate BETWEEN :now AND :cutoff")
    List<Subscription> findTrialsExpiringSoon(
            @Param("now") LocalDateTime now,
            @Param("cutoff") LocalDateTime cutoff);

    // Paid subs expiring within N days — for renewal reminders
    @Query("SELECT s FROM Subscription s WHERE s.status = 'ACTIVE' " +
           "AND s.endDate BETWEEN :now AND :cutoff")
    List<Subscription> findActiveExpiringSoon(
            @Param("now") LocalDateTime now,
            @Param("cutoff") LocalDateTime cutoff);

    // Any TRIAL or ACTIVE whose end date has already passed
    @Query("SELECT s FROM Subscription s WHERE s.status IN ('TRIAL', 'ACTIVE') " +
           "AND s.endDate < :now")
    List<Subscription> findExpiredButNotMarked(@Param("now") LocalDateTime now);
}