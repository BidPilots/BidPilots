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

    // ── Basic lookups (exclude soft-deleted rows) ──────────────────────────────

    @Query("SELECT m FROM MatchedBids m WHERE m.userId = :userId AND m.isDeleted = false")
    List<MatchedBids> findByUserId(@Param("userId") Long userId);

    Optional<MatchedBids> findByUserIdAndBidId(Long userId, Long bidId);

    // ── Unviewed (exclude soft-deleted rows) ──────────────────────────────────

    @Query("SELECT m FROM MatchedBids m WHERE m.userId = :userId AND m.isViewed = false AND m.isDeleted = false")
    List<MatchedBids> findUnviewedByUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(m) FROM MatchedBids m WHERE m.userId = :userId AND m.isViewed = false AND m.isDeleted = false")
    long countUnviewedByUserId(@Param("userId") Long userId);

    // ── Existence check (intentionally includes deleted rows) ─────────────────
    // Key dismiss guard: returns true even for soft-deleted rows so the scheduler
    // never re-inserts a bid the user has dismissed.
    boolean existsByUserIdAndBidId(Long userId, Long bidId);

    // ── Bulk / cleanup deletes ────────────────────────────────────────────────

    // FIX: Added missing @Transactional — @Modifying queries that write to the DB
    // MUST be inside a transaction or Spring throws TransactionRequiredException.
    @Modifying
    @Transactional
    @Query("DELETE FROM MatchedBids m WHERE m.bidId IN " +
           "(SELECT b.id FROM Bid b WHERE b.isFinalized = true OR b.isActive = false)")
    int deleteByInactiveOrFinalizedBids();

    // ── Mark viewed (exclude soft-deleted rows for safety) ────────────────────

    @Modifying
    @Transactional
    @Query("UPDATE MatchedBids m SET m.isViewed = true WHERE m.userId = :userId AND m.id IN :matchIds AND m.isDeleted = false")
    int markAsViewed(@Param("userId") Long userId, @Param("matchIds") List<Long> matchIds);

    // ── Ordered fetch (exclude soft-deleted rows) ─────────────────────────────

    @Query("SELECT m FROM MatchedBids m WHERE m.userId = :userId AND m.isDeleted = false ORDER BY m.matchedAt DESC")
    List<MatchedBids> findByUserIdOrderByMatchedAtDesc(@Param("userId") Long userId);

    // ── Bid-ID set (intentionally includes deleted rows) ──────────────────────
    // Used by the scheduler to build the "already seen" set in one query.
    // Including deleted rows prevents dismissed bids re-appearing.
    @Query("SELECT m.bidId FROM MatchedBids m WHERE m.userId = :userId")
    Set<Long> findBidIdsByUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(m) FROM MatchedBids m WHERE m.filterId = :filterId AND m.isDeleted = false")
    long countByFilterId(@Param("filterId") Long filterId);

    @Modifying
    @Transactional
    @Query("""
        DELETE FROM MatchedBids mb
        WHERE mb.bidId IN (
            SELECT b.id FROM Bid b
            WHERE b.isActive = false
              AND b.isFinalized = false
        )
    """)
    int deleteByTrulyDeadBids();

    // ── Soft-delete a single match for a user ─────────────────────────────────

    @Modifying
    @Transactional
    @Query("""
        UPDATE MatchedBids m
        SET    m.isDeleted = true,
               m.deletedAt = CURRENT_TIMESTAMP
        WHERE  m.id     = :matchId
          AND  m.userId = :userId
          AND  m.isDeleted = false
    """)
    int softDeleteByIdAndUserId(@Param("matchId") Long matchId,
                                @Param("userId")  Long userId);

    // ── Bulk soft-delete ──────────────────────────────────────────────────────

    @Modifying
    @Transactional
    @Query("""
        UPDATE MatchedBids m
        SET    m.isDeleted = true,
               m.deletedAt = CURRENT_TIMESTAMP
        WHERE  m.id     IN :matchIds
          AND  m.userId  = :userId
          AND  m.isDeleted = false
    """)
    int softDeleteByIdsAndUserId(@Param("matchIds") List<Long> matchIds,
                                 @Param("userId")   Long userId);
}