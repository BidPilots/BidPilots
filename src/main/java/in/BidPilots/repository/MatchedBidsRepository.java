package in.BidPilots.repository;

import in.BidPilots.entity.MatchedBids;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.Optional;

@Repository
public interface MatchedBidsRepository extends JpaRepository<MatchedBids, Long> {

    List<MatchedBids> findByUserId(Long userId);

    Optional<MatchedBids> findByUserIdAndBidId(Long userId, Long bidId);

    @Query("SELECT m FROM MatchedBids m WHERE m.userId = :userId AND m.isViewed = false")
    List<MatchedBids> findUnviewedByUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(m) FROM MatchedBids m WHERE m.userId = :userId AND m.isViewed = false")
    long countUnviewedByUserId(@Param("userId") Long userId);

    boolean existsByUserIdAndBidId(Long userId, Long bidId);

    @Modifying
    @Transactional
    @Query("DELETE FROM MatchedBids m WHERE m.bidId IN " +
           "(SELECT b.id FROM Bid b WHERE b.isFinalized = true OR b.isActive = false)")
    int deleteByInactiveOrFinalizedBids();

    @Modifying
    @Transactional
    @Query("UPDATE MatchedBids m SET m.isViewed = true WHERE m.userId = :userId AND m.id IN :matchIds")
    int markAsViewed(@Param("userId") Long userId, @Param("matchIds") List<Long> matchIds);

    // FIX: Added — used by MatchedBidsController to resolve bid details in a single
    // IN query instead of N individual findById calls (eliminates N+1 problem).
    @Query("SELECT m FROM MatchedBids m WHERE m.userId = :userId ORDER BY m.matchedAt DESC")
    List<MatchedBids> findByUserIdOrderByMatchedAtDesc(@Param("userId") Long userId);

    @Query("SELECT m.bidId FROM MatchedBids m WHERE m.userId = :userId")
    Set<Long> findBidIdsByUserId(@Param("userId") Long userId);
}