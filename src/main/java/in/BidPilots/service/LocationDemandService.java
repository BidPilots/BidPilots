package in.BidPilots.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.BidPilots.entity.UserSavedFilter;
import in.BidPilots.repository.CityRepository;
import in.BidPilots.repository.StateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LocationDemandService — demand-driven scraping gate
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * PROBLEM:
 *   GeM has 36 states × 653 cities = up to 23,508 state-city pairs.
 *   Scraping all of them produces 600,000+ bids/day, most of which no user
 *   will ever look at.
 *
 * SOLUTION:
 *   Each State and City row has an is_demanded flag (default false).
 *   When a user saves a filter, this service sets is_demanded = true for
 *   every state and city referenced in that filter.
 *   GeMScrapingService then only iterates over demanded locations, so the
 *   scraper does exactly as much work as your user base requires — no more.
 *
 * DESIGN DECISIONS:
 *   • is_demanded is monotonically increasing (never set back to false).
 *     This keeps old matched_bids intact and avoids complex re-demand logic.
 *   • A state with no demanded cities is still scraped at state level only
 *     (GeMScrapingService falls back gracefully when the city list is empty).
 *   • The UPDATE is a no-op for IDs that are already demanded, so calling
 *     this method repeatedly is safe and efficient.
 *
 * DB MIGRATIONS REQUIRED (run once):
 *   ALTER TABLE states ADD COLUMN is_demanded BOOLEAN DEFAULT FALSE;
 *   CREATE INDEX idx_states_demanded ON states(is_demanded);
 *
 *   ALTER TABLE cities ADD COLUMN is_demanded BOOLEAN DEFAULT FALSE;
 *   CREATE INDEX idx_cities_demanded ON cities(is_demanded);
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LocationDemandService {

    private final StateRepository stateRepository;
    private final CityRepository  cityRepository;
    private final ObjectMapper    objectMapper;

    /**
     * Mark all states and cities in the given filter as demanded.
     *
     * Called by {@link UserSavedFilterService#saveFilter} immediately after
     * the filter is persisted. The bulk UPDATE is a single DB round-trip per
     * entity type — does not load or iterate individual rows.
     *
     * @param filter the newly saved filter whose locations should be demanded
     */
    @Transactional
    public void markDemanded(UserSavedFilter filter) {
        List<Long> stateIds = parseIds(filter.getStateIds());
        List<Long> cityIds  = parseIds(filter.getCityIds());

        if (!stateIds.isEmpty()) {
            stateRepository.markAsDemanded(stateIds);
            log.info("📍 Marked {} state(s) as demanded for filter id={} name='{}'",
                     stateIds.size(), filter.getId(), filter.getFilterName());
        }

        if (!cityIds.isEmpty()) {
            cityRepository.markAsDemanded(cityIds);
            log.info("📍 Marked {} city(ies) as demanded for filter id={} name='{}'",
                     cityIds.size(), filter.getId(), filter.getFilterName());
        }

        if (stateIds.isEmpty() && cityIds.isEmpty()) {
            log.debug("Filter id={} has no location IDs — nothing to demand", filter.getId());
        }
    }

    /**
     * Returns a quick summary of demanded vs total locations.
     * Useful for admin dashboards and health checks.
     */
    public DemandStats getDemandStats() {
        long totalStates    = stateRepository.count();
        long demandedStates = stateRepository.countDemandedStates();
        long totalCities    = cityRepository.count();
        long demandedCities = cityRepository.countDemandedCities();

        return new DemandStats(totalStates, demandedStates, totalCities, demandedCities);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            List<Long> ids = objectMapper.readValue(json, new TypeReference<List<Long>>() {});
            return ids != null ? ids : Collections.emptyList();
        } catch (Exception e) {
            log.warn("LocationDemandService: could not parse ID list '{}': {}", json, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Stats DTO ─────────────────────────────────────────────────────────────

    public record DemandStats(
            long totalStates,
            long demandedStates,
            long totalCities,
            long demandedCities
    ) {
        public double statesCoverage() {
            return totalStates == 0 ? 0 : demandedStates * 100.0 / totalStates;
        }

        public double citiesCoverage() {
            return totalCities == 0 ? 0 : demandedCities * 100.0 / totalCities;
        }

        @Override
        public String toString() {
            return String.format(
                "DemandStats{states=%d/%d (%.1f%%), cities=%d/%d (%.1f%%)}",
                demandedStates, totalStates, statesCoverage(),
                demandedCities, totalCities, citiesCoverage()
            );
        }
    }
}