package in.BidPilots.repository;

import in.BidPilots.entity.Bid;
import in.BidPilots.entity.BidDetails;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface BidDetailsRepository extends JpaRepository<BidDetails, Long> {

    Optional<BidDetails> findByBid(Bid bid);

    Optional<BidDetails> findByBidId(Long bidId);

    @Query("SELECT bd FROM BidDetails bd WHERE bd.extractionStatus = :status")
    List<BidDetails> findByExtractionStatus(@Param("status") String status);

    @Query("SELECT COUNT(bd) FROM BidDetails bd WHERE bd.extractionStatus = :status")
    long countByExtractionStatus(@Param("status") String status);

    @Query("SELECT bd FROM BidDetails bd WHERE bd.extractionStatus = 'PENDING' OR bd.extractionStatus = 'FAILED'")
    List<BidDetails> findPendingExtractions();

    // NEW: Fetch multiple BidDetails by bid IDs
    @Query("SELECT bd FROM BidDetails bd WHERE bd.bid.id IN :bidIds")
    List<BidDetails> findAllByBidIdIn(@Param("bidIds") Set<Long> bidIds);

    @Query("""
        SELECT bd FROM BidDetails bd
        JOIN FETCH bd.bid b
        LEFT JOIN FETCH b.state
        LEFT JOIN FETCH b.consigneeCity
        WHERE bd.extractionStatus = 'COMPLETED'
          AND b.isActive = true
          AND b.isFinalized = false
        """)
    List<BidDetails> findActiveBidsWithCompletedExtraction();

    @Query("""
        SELECT bd FROM BidDetails bd
        JOIN FETCH bd.bid b
        LEFT JOIN FETCH b.state
        LEFT JOIN FETCH b.consigneeCity
        WHERE bd.extractionStatus = 'COMPLETED'
          AND b.isActive = true
          AND b.isFinalized = false
          AND b.state.id IN :stateIds
        """)
    List<BidDetails> findActiveBidsInStates(@Param("stateIds") List<Long> stateIds);

    boolean existsByBid(Bid bid);

    boolean existsByBidId(Long bidId);
    // =========================================================================
    //  FINALIZATION CLEANUP
    // =========================================================================

    /**
     * Count bid_details rows that belong to finalized bids.
     * Used as a quick pre-check before the bulk delete.
     */
    @Query("SELECT COUNT(bd) FROM BidDetails bd WHERE bd.bid.isFinalized = true")
    long countDetailsForFinalizedBids();

    /**
     * Bulk-delete all bid_details rows whose parent bid has been finalized.
     *
     * Called by BidDetailsService.deleteDetailsForFinalizedBids() immediately
     * after BidAutoCloseService sets is_finalized = true on old bids.
     * Keeps the bid_details table lean — no orphan PDF content or cached
     * extraction data for bids that are permanently closed.
     *
     * @return number of rows deleted
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM BidDetails bd WHERE bd.bid.isFinalized = true")
    int deleteDetailsForFinalizedBids();


}