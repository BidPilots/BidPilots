package in.BidPilots.repository;

import in.BidPilots.entity.Boq;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface BoqRepository extends JpaRepository<Boq, Long> {

	@Query("SELECT b.gemBoqId FROM Boq b WHERE b.gemBoqId IS NOT NULL AND b.gemBoqId != ''")
	List<String> findAllGemBoqIds();

	@Query("SELECT DISTINCT LOWER(b.boqTitle) FROM Boq b")
	Set<String> findAllBoqTitlesLowercase();

	@Query("SELECT b.boqTitle FROM Boq b")
	List<String> findAllBoqTitles();

	Optional<Boq> findByGemBoqId(String gemBoqId);

	boolean existsByGemBoqId(String gemBoqId);

	boolean existsByBoqTitleIgnoreCase(String boqTitle);

	@Query("SELECT b FROM Boq b WHERE LOWER(b.boqTitle) LIKE LOWER(CONCAT('%', :keyword, '%'))")
	List<Boq> searchByTitle(@Param("keyword") String keyword);

	@Query("SELECT b FROM Boq b WHERE LOWER(b.boqTitle) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(b.gemBoqId) LIKE LOWER(CONCAT('%', :keyword, '%'))")
	List<Boq> searchByTitleOrGemId(@Param("keyword") String keyword);

	@Query("SELECT COUNT(b) FROM Boq b")
	long countBoqs();

	@Query("SELECT b FROM Boq b WHERE b.isActive = true")
	List<Boq> findAllActive();

	long countByIsActiveTrue();

	List<Boq> findAllByOrderByCreatedAtDesc();

	@Query("SELECT b FROM Boq b WHERE b.isActive = true ORDER BY b.boqTitle")
	List<Boq> findAllActiveOrderedByTitle();

	@Query("SELECT b FROM Boq b ORDER BY b.boqTitle")
	List<Boq> findAllOrderByTitleAsc();

	List<Boq> findByIsActiveTrueOrderByBoqTitleAsc();

	/**
	 * Case-insensitive substring search — used by GET /api/boq/search?q=...
	 */
	List<Boq> findByIsActiveTrueAndBoqTitleContainingIgnoreCaseOrderByBoqTitleAsc(String q);
}