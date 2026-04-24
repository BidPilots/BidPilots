package in.BidPilots.repository;

import in.BidPilots.entity.State;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface StateRepository extends JpaRepository<State, Long> {

    Optional<State> findByStateName(String stateName);

    @Query("SELECT s FROM State s WHERE s.isActive = true ORDER BY s.stateName")
    List<State> findAllActiveStates();

    @Query("SELECT s FROM State s WHERE LOWER(s.stateName) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<State> searchStates(@Param("keyword") String keyword);

    @Modifying
    @Transactional
    @Query("DELETE FROM State s WHERE s.stateName = :stateName")
    void deleteByStateName(@Param("stateName") String stateName);

    // ── Demand-driven scraping ────────────────────────────────────────────────

    /**
     * Returns only the states that at least one user has expressed interest in
     * (via a saved filter). Used by GeMScrapingService to skip undemanded states.
     */
    @Query("SELECT s FROM State s WHERE s.isDemanded = true AND s.isActive = true ORDER BY s.stateName")
    List<State> findDemandedStates();

    /**
     * Marks the given states as demanded in a single bulk UPDATE.
     * Called by LocationDemandService when a user saves a filter.
     */
    @Modifying
    @Transactional
    @Query("UPDATE State s SET s.isDemanded = true WHERE s.id IN :ids")
    void markAsDemanded(@Param("ids") List<Long> ids);

    /**
     * Returns how many states are currently demanded.
     * Useful for admin dashboards and progress reporting.
     */
    @Query("SELECT COUNT(s) FROM State s WHERE s.isDemanded = true")
    long countDemandedStates();
}