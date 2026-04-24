package in.BidPilots.service;

import in.BidPilots.entity.*;
import in.BidPilots.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Matches active bids against each user's saved filters.
 *
 * Called in two contexts:
 *  1. Immediately after a filter is saved (runMatchingForFilter).
 *  2. On a 15-minute scheduled job (runMatchingForAllUsers / runMatchingForNewBids).
 *
 * KEY DISMISS GUARD
 * -----------------
 * existsByUserIdAndBidId queries ALL rows in matched_bids — including rows where
 * isDeleted = true (dismissed bids). A dismissed bid must never reappear, so we
 * treat "row exists" as "bid has been seen", regardless of the isDeleted flag.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserBidMatchingService {

    private final UserSavedFilterRepository  savedFilterRepository;
    private final MatchedBidsRepository      matchedBidsRepository;
    private final BidRepository              bidRepository;
    private final BidDetailsRepository       bidDetailsRepository;
    // FIX #1: Inject CategoryRepository so resolveCategoryName() can actually look up names
    private final CategoryRepository         categoryRepository;
    private final ObjectMapper               objectMapper = new ObjectMapper();

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int    MIN_SMART_SCORE    = 2;
    private static final double JACCARD_THRESHOLD  = 0.15;

    // =========================================================================
    //  PUBLIC API
    // =========================================================================

    /**
     * Called right after a new filter is saved — async so the HTTP response
     * returns immediately while matching happens in the background.
     */
    @Async
    public void runMatchingForFilter(Long userId, UserSavedFilter filter) {
        log.info("[Matching] Starting async match for userId={} filterId={}", userId, filter.getId());
        try {
            List<Bid> activeBids = bidRepository.findByIsActiveTrueAndIsFinalizedFalse();

            // FIX #3: Batch-load BidDetails for all active bids upfront (avoids N+1)
            Set<Long> bidIds = activeBids.stream().map(Bid::getId).collect(Collectors.toSet());
            Map<Long, BidDetails> detailsMap = loadDetailsMap(bidIds);

            matchBidsForFilter(userId, filter, activeBids, detailsMap);
        } catch (Exception e) {
            log.error("[Matching] Error in async match for filterId={}: {}", filter.getId(), e.getMessage(), e);
        }
    }

    /**
     * FIX #2: Added @Scheduled — without this annotation the method is NEVER
     * called automatically and the 15-minute matching job never runs.
     *
     * Runs every 15 minutes. Matches ALL users against ALL active bids.
     * Only inserts rows that do not yet exist in matched_bids.
     */
    @Scheduled(fixedRate = 15 * 60 * 1000) // every 15 minutes
    @Transactional
    public void runMatchingForAllUsers() {
        log.info("[Scheduler] Starting full matching run for all users");
        List<UserSavedFilter> allFilters = savedFilterRepository.findAll();
        List<Bid> activeBids = bidRepository.findByIsActiveTrueAndIsFinalizedFalse();

        if (allFilters.isEmpty() || activeBids.isEmpty()) {
            log.info("[Scheduler] No filters or no active bids — skipping run");
            return;
        }

        // FIX #3: Batch-load ALL BidDetails in one query instead of per-bid inside the loop
        Set<Long> bidIds = activeBids.stream().map(Bid::getId).collect(Collectors.toSet());
        Map<Long, BidDetails> detailsMap = loadDetailsMap(bidIds);

        Map<Long, List<UserSavedFilter>> filtersByUser = allFilters.stream()
                .collect(Collectors.groupingBy(UserSavedFilter::getUserId));

        int totalInserted = 0;
        for (Map.Entry<Long, List<UserSavedFilter>> entry : filtersByUser.entrySet()) {
            Long userId = entry.getKey();
            for (UserSavedFilter filter : entry.getValue()) {
                totalInserted += matchBidsForFilter(userId, filter, activeBids, detailsMap);
            }
        }
        log.info("[Scheduler] Completed full matching run — {} new matches inserted", totalInserted);
    }

    /**
     * Lighter variant: only considers bids created since the last scheduler run.
     * Same dismiss-guard guarantee applies via existsByUserIdAndBidId.
     */
    @Transactional
    public void runMatchingForNewBids(List<Bid> newBids) {
        if (newBids == null || newBids.isEmpty()) return;
        log.info("[Scheduler] Matching {} new bid(s) against all filters", newBids.size());

        List<UserSavedFilter> allFilters = savedFilterRepository.findAll();

        // FIX #3: Batch-load BidDetails for new bids upfront
        Set<Long> bidIds = newBids.stream().map(Bid::getId).collect(Collectors.toSet());
        Map<Long, BidDetails> detailsMap = loadDetailsMap(bidIds);

        Map<Long, List<UserSavedFilter>> filtersByUser = allFilters.stream()
                .collect(Collectors.groupingBy(UserSavedFilter::getUserId));

        int totalInserted = 0;
        for (Map.Entry<Long, List<UserSavedFilter>> entry : filtersByUser.entrySet()) {
            Long userId = entry.getKey();
            for (UserSavedFilter filter : entry.getValue()) {
                totalInserted += matchBidsForFilter(userId, filter, newBids, detailsMap);
            }
        }
        log.info("[Scheduler] New-bid matching done — {} new matches inserted", totalInserted);
    }

    // =========================================================================
    //  CORE MATCHING LOGIC
    // =========================================================================

    /**
     * Matches a list of bids against a single filter for a user.
     *
     * @param detailsMap pre-loaded BidDetails keyed by bidId (avoids N+1 queries)
     * @return number of new MatchedBids rows inserted
     */
    private int matchBidsForFilter(Long userId, UserSavedFilter filter,
                                   List<Bid> bids, Map<Long, BidDetails> detailsMap) {
        String filterType = filter.getFilterType() != null
                ? filter.getFilterType().toUpperCase() : "SMART";

        List<Long> stateIds = parseIds(filter.getStateIds());
        List<Long> cityIds  = parseIds(filter.getCityIds());

        // FIX #4: Resolve category name ONCE per filter, not once per bid.
        // Previously resolveCategoryName() was called inside matchCategoryFilter()
        // which is called inside the bid loop — one DB hit per bid × per filter run.
        String resolvedCategoryName = null;
        if (!"BOQ".equals(filterType) && !"LOCATION".equals(filterType)) {
            resolvedCategoryName = resolveCategoryName(filter.getCategoryId());
            // If category can't be resolved, skip the whole filter — no bids will match.
            if (resolvedCategoryName == null) {
                log.warn("[Matching] categoryId={} not found for filterId={} — skipping",
                        filter.getCategoryId(), filter.getId());
                return 0;
            }
        }

        // FIX #5: Pre-load the full set of already-seen bid IDs for this user in ONE query
        // instead of calling existsByUserIdAndBidId() (one DB round-trip) per bid.
        Set<Long> alreadySeenBidIds = matchedBidsRepository.findBidIdsByUserId(userId);

        int inserted = 0;

        for (Bid bid : bids) {
            // DISMISS GUARD: findBidIdsByUserId returns ALL bid IDs including soft-deleted rows,
            // so a dismissed bid is never re-inserted.
            if (alreadySeenBidIds.contains(bid.getId())) {
                continue;
            }

            // Use pre-loaded details map — no extra DB query per bid
            BidDetails details = detailsMap.get(bid.getId());
            boolean matched = false;

            switch (filterType) {
                case "BOQ":
                    matched = matchBoqFilter(filter, bid, details, stateIds, cityIds);
                    break;
                case "LOCATION":
                    matched = matchLocationFilter(bid, stateIds, cityIds);
                    break;
                default: // SMART / EXACT / BROAD
                    matched = matchCategoryFilter(resolvedCategoryName, filterType, bid, details, stateIds, cityIds);
                    break;
            }

            if (matched) {
                MatchedBids record = new MatchedBids();
                record.setUserId(userId);
                record.setBidId(bid.getId());
                record.setFilterId(filter.getId());
                record.setCategoryId(filter.getCategoryId());
                record.setIsViewed(false);
                record.setIsDeleted(false);
                matchedBidsRepository.save(record);
                inserted++;
            }
        }

        if (inserted > 0) {
            log.info("[Matching] userId={} filterId={} type={} → {} new match(es)",
                    userId, filter.getId(), filterType, inserted);
        }
        return inserted;
    }

    // ── Filter-type matchers ──────────────────────────────────────────────────

    private boolean matchLocationFilter(Bid bid, List<Long> stateIds, List<Long> cityIds) {
        if (!locationMatches(bid, stateIds, cityIds)) return false;
        return Boolean.TRUE.equals(bid.getIsActive()) && !Boolean.TRUE.equals(bid.getIsFinalized());
    }

    private boolean matchBoqFilter(UserSavedFilter filter, Bid bid, BidDetails details,
                                   List<Long> stateIds, List<Long> cityIds) {
        if (!locationMatches(bid, stateIds, cityIds)) return false;

        String keyword = filter.getBoqTitle();
        if (keyword == null || keyword.isBlank()) return false;
        String kwLower = keyword.trim().toLowerCase();

        if (details != null && details.getPdfContent() != null
                && details.getPdfContent().toLowerCase().contains(kwLower)) return true;
        if (details != null && details.getItemCategory() != null
                && details.getItemCategory().toLowerCase().contains(kwLower)) return true;
        if (bid.getItems() != null && bid.getItems().toLowerCase().contains(kwLower)) return true;
        return bid.getDataContent() != null && bid.getDataContent().toLowerCase().contains(kwLower);
    }

    // resolvedCategoryName is pre-computed once per filter run (not per bid) — see FIX #4
    private boolean matchCategoryFilter(String resolvedCategoryName, String filterType,
                                        Bid bid, BidDetails details,
                                        List<Long> stateIds, List<Long> cityIds) {
        if (!locationMatches(bid, stateIds, cityIds)) return false;
        if (resolvedCategoryName == null) return false;

        String itemCategory = details != null ? details.getItemCategory() : null;

        return switch (filterType) {
            case "EXACT"  -> exactMatch(resolvedCategoryName, itemCategory, bid);
            case "BROAD"  -> broadMatch(resolvedCategoryName, itemCategory, bid);
            default       -> smartMatch(resolvedCategoryName, itemCategory, bid); // SMART
        };
    }

    // ── Matching strategies ───────────────────────────────────────────────────

    private boolean exactMatch(String filterCategory, String itemCategory, Bid bid) {
        if (itemCategory != null && itemCategory.toLowerCase().contains(filterCategory)) return true;
        if (bid.getItems() != null && bid.getItems().toLowerCase().contains(filterCategory)) return true;
        return bid.getDataContent() != null && bid.getDataContent().toLowerCase().contains(filterCategory);
    }

    private boolean broadMatch(String filterCategory, String itemCategory, Bid bid) {
        Set<String> filterTokens = tokenize(filterCategory);
        String haystack = buildHaystack(itemCategory, bid);
        Set<String> haystackTokens = tokenize(haystack);
        long hits = filterTokens.stream().filter(haystackTokens::contains).count();
        return hits > 0;
    }

    private boolean smartMatch(String filterCategory, String itemCategory, Bid bid) {
        Set<String> filterTokens = tokenize(filterCategory);
        String haystack = buildHaystack(itemCategory, bid);
        Set<String> haystackTokens = tokenize(haystack);

        long hits = filterTokens.stream().filter(haystackTokens::contains).count();
        if (hits < MIN_SMART_SCORE) return false;

        Set<String> intersection = new HashSet<>(filterTokens);
        intersection.retainAll(haystackTokens);
        Set<String> union = new HashSet<>(filterTokens);
        union.addAll(haystackTokens);
        double jaccard = union.isEmpty() ? 0 : (double) intersection.size() / union.size();
        return jaccard >= JACCARD_THRESHOLD;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean locationMatches(Bid bid, List<Long> stateIds, List<Long> cityIds) {
        if (stateIds.isEmpty()) return true;
        Long bidStateId = bid.getState() != null ? bid.getState().getId() : null;
        if (bidStateId == null || !stateIds.contains(bidStateId)) return false;
        if (cityIds.isEmpty()) return true;
        Long bidCityId = bid.getConsigneeCity() != null ? bid.getConsigneeCity().getId() : null;
        return bidCityId != null && cityIds.contains(bidCityId);
    }

    private String buildHaystack(String itemCategory, Bid bid) {
        StringBuilder sb = new StringBuilder();
        if (itemCategory   != null) sb.append(itemCategory).append(' ');
        if (bid.getItems() != null) sb.append(bid.getItems()).append(' ');
        if (bid.getDataContent() != null) sb.append(bid.getDataContent());
        return sb.toString().toLowerCase();
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        return Arrays.stream(text.toLowerCase().split("[\\s,/\\-()]+"))
                .filter(t -> t.length() > 2)
                .collect(Collectors.toSet());
    }

    /**
     * FIX #1: Was always returning null, causing ALL category-based filters
     * (SMART / EXACT / BROAD) to silently skip every bid.
     * Now properly queries the CategoryRepository.
     */
    private String resolveCategoryName(Long categoryId) {
        if (categoryId == null) return null;
        return categoryRepository.findById(categoryId)
                .map(c -> c.getCategoryName().toLowerCase())
                .orElse(null);
    }

    /**
     * FIX #3: Batch-loads BidDetails for a set of bid IDs in a single query.
     * Previously, findByBidId() was called inside the per-bid loop, causing
     * one DB round-trip per bid (N+1 problem).
     */
    private Map<Long, BidDetails> loadDetailsMap(Set<Long> bidIds) {
        if (bidIds.isEmpty()) return Collections.emptyMap();
        return bidDetailsRepository.findAllByBidIdIn(bidIds)
                .stream()
                .collect(Collectors.toMap(bd -> bd.getBid().getId(), bd -> bd));
    }

    private List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse IDs JSON '{}': {}", json, e.getMessage());
            return Collections.emptyList();
        }
    }
}