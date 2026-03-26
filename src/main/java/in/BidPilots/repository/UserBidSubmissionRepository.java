package in.BidPilots.repository;

import in.BidPilots.entity.UserBidSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserBidSubmissionRepository extends JpaRepository<UserBidSubmission, Long> {

    /** All submissions for a user, newest first */
    List<UserBidSubmission> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** Specific submission for a user+bid pair */
    Optional<UserBidSubmission> findByUserIdAndBidId(Long userId, Long bidId);

    /** Check if user already submitted a price for this bid */
    boolean existsByUserIdAndBidId(Long userId, Long bidId);

    /** All submissions for a specific bid (to show bid-level stats) */
    List<UserBidSubmission> findByBidId(Long bidId);

    /** Count of SUBMITTED entries for a bid — used for stats */
    @Query("SELECT COUNT(s) FROM UserBidSubmission s WHERE s.bidId = :bidId AND s.status = 'SUBMITTED'")
    long countSubmittedByBidId(@Param("bidId") Long bidId);

    /** Count of SUBMITTED entries for a user */
    @Query("SELECT COUNT(s) FROM UserBidSubmission s WHERE s.userId = :userId AND s.status = 'SUBMITTED'")
    long countSubmittedByUserId(@Param("userId") Long userId);

    /** All SUBMITTED entries for a user, newest first — for the submissions list */
    @Query("SELECT s FROM UserBidSubmission s WHERE s.userId = :userId AND s.status != 'WITHDRAWN' ORDER BY s.updatedAt DESC")
    List<UserBidSubmission> findActiveByUserId(@Param("userId") Long userId);

    /**
     * Hard-delete by owner — used when a user withdraws their submission.
     * Returns number of rows deleted (0 = not found or wrong owner).
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM UserBidSubmission s WHERE s.id = :id AND s.userId = :userId")
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}