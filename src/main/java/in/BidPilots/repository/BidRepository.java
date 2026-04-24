package in.BidPilots.repository;

import in.BidPilots.entity.Bid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public interface BidRepository extends JpaRepository<Bid, Long> {

	Optional<Bid> findByBidNumber(String bidNumber);

	Optional<Bid> findByRaNumber(String raNumber);

	List<Bid> findByBidType(String bidType);

	List<Bid> findByIsActiveTrue();

	List<Bid> findByIsDeactiveTrue();

	// ========== IS_FINALIZED METHODS ==========

	List<Bid> findByIsFinalized(Boolean isFinalized);

	List<Bid> findByIsFinalizedFalse();

	List<Bid> findByIsFinalizedTrue();

	long countByIsFinalized(Boolean isFinalized);

	long countByIsFinalizedFalse();

	long countByIsFinalizedTrue();

	@Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END FROM Bid b WHERE b.bidNumber = :bidNumber")
	boolean existsByBidNumber(@Param("bidNumber") String bidNumber);

	@Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END FROM Bid b WHERE b.raNumber = :raNumber")
	boolean existsByRaNumber(@Param("raNumber") String raNumber);

	@Query("SELECT b FROM Bid b WHERE b.bidEndDate BETWEEN :start AND :end")
	List<Bid> findBidsClosingBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

	@Query("SELECT COUNT(b) FROM Bid b WHERE b.bidEndDate > :now")
	long countActiveBids(@Param("now") LocalDateTime now);

	@Query("SELECT COUNT(b) FROM Bid b WHERE b.state IS NOT NULL")
	long countBidsWithState();

	@Query("SELECT COUNT(b) FROM Bid b WHERE b.consigneeCity IS NOT NULL")
	long countBidsWithCity();

	@Query("SELECT COUNT(b) FROM Bid b WHERE b.items IS NOT NULL AND b.items != ''")
	long countBidsWithItems();

	@Query("SELECT COUNT(b) FROM Bid b WHERE b.dataContent IS NOT NULL AND b.dataContent != ''")
	long countBidsWithDataContent();

	@Query("SELECT COUNT(b) FROM Bid b WHERE b.department IS NOT NULL AND b.department != ''")
	long countBidsWithDepartment();

	@Query("SELECT b FROM Bid b WHERE b.state.id = :stateId")
	List<Bid> findByStateId(@Param("stateId") Long stateId);

	@Query("SELECT b FROM Bid b WHERE b.consigneeCity.id = :cityId")
	List<Bid> findByConsigneeCityId(@Param("cityId") Long cityId);

	@Query("SELECT b FROM Bid b WHERE b.state.id = :stateId AND b.consigneeCity.id = :cityId")
	List<Bid> findByStateIdAndConsigneeCityId(@Param("stateId") Long stateId, @Param("cityId") Long cityId);

	@Query("SELECT b FROM Bid b WHERE " + "LOWER(b.bidNumber) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
			+ "LOWER(b.raNumber) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
			+ "LOWER(b.items) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
			+ "LOWER(b.dataContent) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
			+ "LOWER(b.department) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
			+ "LOWER(b.ministry) LIKE LOWER(CONCAT('%', :keyword, '%'))")
	List<Bid> searchBids(@Param("keyword") String keyword);

	@Query("SELECT new map(s.id as id, s.stateName as name, COUNT(b) as count) "
			+ "FROM State s LEFT JOIN s.bids b GROUP BY s.id ORDER BY s.stateName")
	List<Map<String, Object>> getStatesWithBidCount();

	@Query(value = "SELECT COUNT(DISTINCT state_id) FROM bids WHERE state_id IS NOT NULL", nativeQuery = true)
	long countDistinctStatesWithBids();

	@Query(value = "SELECT COUNT(DISTINCT city_id) FROM bids WHERE city_id IS NOT NULL", nativeQuery = true)
	long countDistinctCitiesWithBids();

	@Query("SELECT b FROM Bid b ORDER BY b.id DESC LIMIT 10")
	List<Bid> findTop10ByOrderByIdDesc();

	@Query("SELECT b FROM Bid b WHERE b.isActive = true AND b.bidEndDate <= :now")
	List<Bid> findActiveBidsExpiredBefore(@Param("now") LocalDateTime now);

	@Query(value = "SELECT * FROM bids WHERE is_active = 1 AND bid_end_date <= :now", nativeQuery = true)
	List<Bid> findActiveBidsExpiredBeforeNative(@Param("now") LocalDateTime now);

	@Query("SELECT COUNT(b) FROM Bid b WHERE b.isActive = true AND b.bidEndDate BETWEEN :now AND :oneMinuteLater")
	long countBidsExpiringInNextMinute(@Param("now") LocalDateTime now,
			@Param("oneMinuteLater") LocalDateTime oneMinuteLater);

	@Query("SELECT b FROM Bid b WHERE b.isActive = true AND b.bidEndDate <= :now")
	List<Bid> findActiveExpiredBids(@Param("now") LocalDateTime now);

	@Query("SELECT COUNT(b) FROM Bid b WHERE b.isActive = true AND b.bidEndDate <= :now")
	long countExpiredBids(@Param("now") LocalDateTime now);

	/**
	 * Find expired bids batch with pagination
	 */
	@Query("SELECT b FROM Bid b WHERE b.isActive = true AND b.bidEndDate <= :now")
	List<Bid> findExpiredBidsBatch(@Param("now") LocalDateTime now, Pageable pageable);

	@Query("SELECT COUNT(b) FROM Bid b WHERE b.isActive = true")
	long countActiveBids();

	@Query("SELECT COUNT(b) FROM Bid b WHERE b.isDeactive = true")
	long countDeactiveBids();

	@Query("SELECT b FROM Bid b WHERE b.isActive = true AND b.bidEndDate BETWEEN :now AND :nextHour")
	List<Bid> findBidsExpiringInNextHour(@Param("now") LocalDateTime now, @Param("nextHour") LocalDateTime nextHour);

	/**
	 * Bulk update to deactivate expired bids
	 */
	@Modifying
	@Transactional
	@Query("UPDATE Bid b SET b.isActive = false, b.isDeactive = true WHERE b.isActive = true AND b.bidEndDate <= :now")
	int bulkDeactivateExpiredBids(@Param("now") LocalDateTime now);

	// ========== OPTIMIZED METHODS FOR IS_FINALIZED ==========

	/**
	 * OPTIMIZED: Check if bid exists only among non-finalized bids
	 */
	@Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END FROM Bid b "
			+ "WHERE b.bidNumber = :bidNumber AND b.isFinalized = false")
	boolean existsByBidNumberAndNotFinalized(@Param("bidNumber") String bidNumber);

	/**
	 * OPTIMIZED: Find bid by number only among non-finalized bids
	 */
	@Query("SELECT b FROM Bid b WHERE b.bidNumber = :bidNumber AND b.isFinalized = false")
	Optional<Bid> findByBidNumberAndNotFinalized(@Param("bidNumber") String bidNumber);

	/**
	 * OPTIMIZED: Check if RA exists only among non-finalized bids
	 */
	@Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END FROM Bid b "
			+ "WHERE b.raNumber = :raNumber AND b.isFinalized = false")
	boolean existsByRaNumberAndNotFinalized(@Param("raNumber") String raNumber);

	/**
	 * OPTIMIZED: Find RA by number only among non-finalized bids
	 */
	@Query("SELECT b FROM Bid b WHERE b.raNumber = :raNumber AND b.isFinalized = false")
	Optional<Bid> findByRaNumberAndNotFinalized(@Param("raNumber") String raNumber);

	/**
	 * Bulk update to finalize bids older than 3 days based on createdDate
	 */
	@Modifying
	@Transactional
	@Query("UPDATE Bid b SET b.isFinalized = true WHERE b.isFinalized = false AND b.createdDate < :cutoffDate")
	int bulkFinalizeOldBidsByCreatedDate(@Param("cutoffDate") LocalDateTime cutoffDate);

	/**
	 * Count non-finalized bids older than 3 days based on createdDate
	 */
	@Query("SELECT COUNT(b) FROM Bid b WHERE b.isFinalized = false AND b.createdDate < :cutoffDate")
	long countNonFinalizedOldBidsByCreatedDate(@Param("cutoffDate") LocalDateTime cutoffDate);

	/**
	 * Batch find non-finalized bids older than 3 days based on createdDate
	 */
	@Query("SELECT b FROM Bid b WHERE b.isFinalized = false AND b.createdDate < :cutoffDate")
	List<Bid> findNonFinalizedOldBidsBatchByCreatedDate(@Param("cutoffDate") LocalDateTime cutoffDate, Pageable pageable);

	// ========== NEW METHODS FOR BID END DATE BASED FINALIZATION ==========

	/**
	 * Count bids that ended 3+ days ago and are not finalized
	 * This is the correct logic for isFinalized = true
	 */
	@Query("SELECT COUNT(b) FROM Bid b WHERE b.bidEndDate <= :cutoffDate AND b.isFinalized = false")
	long countBidsToFinalize(@Param("cutoffDate") LocalDateTime cutoffDate);

	/**
	 * Bulk finalize bids that ended 3+ days ago
	 * This sets isFinalized = true for bids that ended 3 or more days ago
	 */
	@Modifying
	@Transactional
	@Query("UPDATE Bid b SET b.isFinalized = true WHERE b.bidEndDate <= :cutoffDate AND b.isFinalized = false")
	int bulkFinalizeOldBidsByEndDate(@Param("cutoffDate") LocalDateTime cutoffDate);

	/**
	 * Batch find bids to finalize based on end date
	 */
	@Query("SELECT b FROM Bid b WHERE b.bidEndDate <= :cutoffDate AND b.isFinalized = false")
	List<Bid> findBidsToFinalizeBatch(@Param("cutoffDate") LocalDateTime cutoffDate, Pageable pageable);
	
	// Add this method to BidRepository.java

	/**
	 * Find all active bids that are not finalized
	 */
	@Query("SELECT b FROM Bid b WHERE b.isActive = true AND b.isFinalized = false")
	List<Bid> findByIsActiveTrueAndIsFinalizedFalse();
}