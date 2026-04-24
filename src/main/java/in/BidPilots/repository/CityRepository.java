package in.BidPilots.repository;

import in.BidPilots.entity.City;
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
public interface CityRepository extends JpaRepository<City, Long> {

    List<City> findByState(State state);

    List<City> findByStateId(Long stateId);

    Optional<City> findByCityNameAndState(String cityName, State state);

    @Query("SELECT c FROM City c WHERE c.state.id = :stateId AND c.isActive = true ORDER BY c.cityName")
    List<City> findActiveCitiesByState(@Param("stateId") Long stateId);

    long countByState(State state);

    @Modifying
    @Transactional
    @Query("DELETE FROM City c WHERE c.state = :state")
    void deleteByState(@Param("state") State state);

    // ── Demand-driven scraping ────────────────────────────────────────────────

    /**
     * Returns all cities that at least one user has selected in a saved filter.
     * Used by GeMScrapingService to skip undemanded cities.
     */
    @Query("SELECT c FROM City c WHERE c.isDemanded = true AND c.isActive = true")
    List<City> findDemandedCities();

    /**
     * Returns demanded cities for a specific state.
     * Used when the scraper is iterating city-by-city within a demanded state.
     * Falls back gracefully: if a state is demanded but none of its cities are,
     * GeMScrapingService will scrape at state level only (no city filter).
     */
    @Query("SELECT c FROM City c WHERE c.state.id = :stateId AND c.isDemanded = true AND c.isActive = true ORDER BY c.cityName")
    List<City> findDemandedCitiesByState(@Param("stateId") Long stateId);

    /**
     * Marks the given cities as demanded in a single bulk UPDATE.
     * Called by LocationDemandService when a user saves a filter.
     */
    @Modifying
    @Transactional
    @Query("UPDATE City c SET c.isDemanded = true WHERE c.id IN :ids")
    void markAsDemanded(@Param("ids") List<Long> ids);

    /**
     * Returns how many cities are currently demanded.
     * Useful for admin dashboards and progress reporting.
     */
    @Query("SELECT COUNT(c) FROM City c WHERE c.isDemanded = true")
    long countDemandedCities();
}