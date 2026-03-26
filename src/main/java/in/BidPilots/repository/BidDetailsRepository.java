package in.BidPilots.repository;

import in.BidPilots.entity.Bid;
import in.BidPilots.entity.BidDetails;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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

    /**
     * UPDATED: Added JOIN FETCH bd.bid b so the Bid (including its State and
     * ConsigneeCity relationships) is loaded in one SQL JOIN.
     *
     * Without JOIN FETCH, calling details.getBid() inside the matching loop
     * triggers one lazy SELECT per row — O(N) extra queries for N bids.
     * With JOIN FETCH it is a single query regardless of result size.
     *
     * The rest of the WHERE clause is unchanged from the original.
     */
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

    /**
     * NEW: Same as above but pre-filtered to a specific set of state IDs.
     *
     * Used by UserBidMatchingService when a filter has stateIds set — instead
     * of loading every active bid and discarding non-matching states in Java,
     * the WHERE clause pushes the restriction to the database.
     *
     * Example: filter has stateIds = [5, 12] (Maharashtra, Karnataka).
     * Only BidDetails whose linked Bid has state_id IN (5, 12) are returned.
     */
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
}