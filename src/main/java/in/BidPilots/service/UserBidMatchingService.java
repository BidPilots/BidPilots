package in.BidPilots.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.BidPilots.entity.BidDetails;
import in.BidPilots.entity.Category;
import in.BidPilots.entity.MatchedBids;
import in.BidPilots.entity.User;
import in.BidPilots.entity.UserSavedFilter;
import in.BidPilots.repository.BidDetailsRepository;
import in.BidPilots.repository.CategoryRepository;
import in.BidPilots.repository.MatchedBidsRepository;
import in.BidPilots.repository.UserRegistrationRepository;
import in.BidPilots.repository.UserSavedFilterRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserBidMatchingService {

    private final UserSavedFilterRepository  userSavedFilterRepository;
    private final UserRegistrationRepository userRepository;
    private final BidDetailsRepository       bidDetailsRepository;
    private final MatchedBidsRepository      matchedBidsRepository;
    private final CategoryRepository         categoryRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Statistics ────────────────────────────────────────────────────────────
    private final AtomicInteger totalRuns             = new AtomicInteger(0);
    private final AtomicInteger totalUsersProcessed   = new AtomicInteger(0);
    private final AtomicInteger totalFiltersProcessed = new AtomicInteger(0);
    private final AtomicInteger totalBidsScanned      = new AtomicInteger(0);
    private final AtomicInteger totalMatchesFound     = new AtomicInteger(0);
    private final AtomicInteger totalNewMatches       = new AtomicInteger(0);
    private final AtomicInteger totalDuplicates       = new AtomicInteger(0);

    private volatile LocalDateTime lastRunTime = null;
    private final AtomicBoolean    isRunning   = new AtomicBoolean(false);

    // volatile — reference swap visible to all threads immediately
    private volatile Map<Long, String> categoryNameCache = new HashMap<>();

    // ═══════════════════════════════════════════════════════════════════════════
    //  BOOT
    // ═══════════════════════════════════════════════════════════════════════════
    @PostConstruct
    public void init() {
        log.info("=".repeat(80));
        log.info("🚀 USER BID MATCHING SERVICE INITIALIZED");
        log.info("   Scheduled full scan : every 15 minutes");
        log.info("   Immediate scan      : triggered on filter creation");
        log.info("   Match criteria      : category in pdf_content  +  state/city filter");
        log.info("=".repeat(80));
        loadCategoryCache();
    }

    private void loadCategoryCache() {
        try {
            categoryNameCache = categoryRepository.findAll()
                    .stream()
                    .collect(Collectors.toMap(Category::getId, Category::getCategoryName));
            log.info("📚 Category cache loaded: {} categories", categoryNameCache.size());
        } catch (Exception e) {
            log.error("Error loading category cache: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  IMMEDIATE MATCH — called right after a filter is saved
    //  Runs @Async so the HTTP response is not blocked.
    // ═══════════════════════════════════════════════════════════════════════════
    @Async
    @Transactional
    public void runMatchingForFilter(Long userId, UserSavedFilter filter) {
        log.info("⚡ Immediate match: user={} filter='{}' (id={})",
                userId, filter.getFilterName(), filter.getId());
        try {
            String categoryName = resolvedCategoryName(filter.getCategoryId());
            if (categoryName == null) {
                log.warn("⚠️ Category {} not in cache — skipping", filter.getCategoryId());
                return;
            }

            List<Long> filterStateIds = parseIds(filter.getStateIds());
            List<Long> filterCityIds  = parseIds(filter.getCityIds());

            // Pull only active bids — pre-filtered by state at DB level if states set
            List<BidDetails> candidates = filterStateIds.isEmpty()
                    ? bidDetailsRepository.findActiveBidsWithCompletedExtraction()
                    : bidDetailsRepository.findActiveBidsInStates(filterStateIds);

            if (candidates.isEmpty()) {
                log.info("ℹ️ No candidate bids (user={} filter={})", userId, filter.getId());
                return;
            }

            // Load all already-matched bid IDs for this user in ONE query
            Set<Long> alreadyMatched = matchedBidsRepository.findBidIdsByUserId(userId);

            List<MatchedBids> toSave = new ArrayList<>();

            for (BidDetails details : candidates) {
                // BidDetails.bid is @OneToOne LAZY, but the repository query uses
                // JOIN FETCH so details.getBid() is already loaded — no extra query.
                Long bidId = details.getBid().getId();

                if (alreadyMatched.contains(bidId)) continue;

                if (!matchesAllCriteria(details, categoryName, filterStateIds, filterCityIds)) continue;

                MatchedBids match = new MatchedBids();
                match.setUserId(userId);
                match.setBidId(bidId);
                match.setFilterId(filter.getId());
                match.setCategoryId(filter.getCategoryId());
                match.setIsViewed(false);
                toSave.add(match);
            }

            if (!toSave.isEmpty()) {
                matchedBidsRepository.saveAll(toSave);
            }

            log.info("✅ Immediate match done: filter='{}' → {} new matches",
                    filter.getFilterName(), toSave.size());

        } catch (Exception e) {
            log.error("❌ Immediate match error for filter {}: {}", filter.getId(), e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SCHEDULED FULL SCAN — every 15 minutes, all users & all filters
    // ═══════════════════════════════════════════════════════════════════════════
    @Scheduled(cron = "0 */15 * * * *")
    @Transactional
    public void runMatchingJob() {
        if (!isRunning.compareAndSet(false, true)) {
            log.info("⏭️ Previous scan still running — skipping this tick");
            return;
        }

        lastRunTime   = LocalDateTime.now();
        int runNumber = totalRuns.incrementAndGet();

        log.info("=".repeat(80));
        log.info("🔍 MATCHING JOB #{} STARTED — {}", runNumber, lastRunTime);
        log.info("=".repeat(80));

        long startTime = System.currentTimeMillis();

        try {
            resetCounters();
            loadCategoryCache();

            List<User> users = userRepository.findAll();
            log.info("📊 Users to process: {}", users.size());

            // Load ALL active bids ONCE — shared across all users/filters in this run.
            // JOIN FETCH ensures bid.state and bid.consigneeCity are pre-loaded.
            List<BidDetails> activeBids = bidDetailsRepository.findActiveBidsWithCompletedExtraction();
            log.info("📄 Active bid details: {}", activeBids.size());
            totalBidsScanned.set(activeBids.size());

            if (activeBids.isEmpty()) {
                log.info("ℹ️ No active bids — nothing to match");
                return;
            }

            for (User user : users) {
                processUser(user, activeBids);
                int done = totalUsersProcessed.incrementAndGet();
                if (done % 10 == 0) {
                    log.info("   Progress: {}/{} users", done, users.size());
                }
            }

            // Remove matches whose bids are now finalized or inactive
            int deleted = matchedBidsRepository.deleteByInactiveOrFinalizedBids();
            if (deleted > 0) log.info("🧹 Removed {} stale matches", deleted);

            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            log.info("=".repeat(80));
            log.info("✅ MATCHING JOB #{} DONE in {}s", runNumber, elapsed);
            log.info("   Users={} Filters={} Bids={}  New={} Dups={}",
                    totalUsersProcessed.get(), totalFiltersProcessed.get(),
                    totalBidsScanned.get(), totalNewMatches.get(), totalDuplicates.get());
            log.info("=".repeat(80));

        } catch (Exception e) {
            log.error("❌ Matching job #{} failed: {}", runNumber, e.getMessage(), e);
        } finally {
            isRunning.set(false); // ALWAYS reset — even if an exception is thrown
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Per-user processing
    // ─────────────────────────────────────────────────────────────────────────
    private void processUser(User user, List<BidDetails> activeBids) {
        try {
            List<UserSavedFilter> filters = userSavedFilterRepository
                    .findByUserIdWithCategory(user.getId());

            if (filters.isEmpty()) return;

            // Fetch the complete set of already-matched bid IDs for this user
            // in ONE query, then use Set.contains() — O(1) per lookup.
            Set<Long> alreadyMatched = new HashSet<>(
                    matchedBidsRepository.findBidIdsByUserId(user.getId())
            );

            List<MatchedBids> toSave = new ArrayList<>();

            for (UserSavedFilter filter : filters) {
                processFilter(user, filter, activeBids, alreadyMatched, toSave);
                totalFiltersProcessed.incrementAndGet();
            }

            // Batch insert — one saveAll per user, not one save per match
            if (!toSave.isEmpty()) {
                matchedBidsRepository.saveAll(toSave);
            }

        } catch (Exception e) {
            log.error("Error processing user {}: {}", user.getId(), e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Per-filter processing — apply all criteria and collect new matches
    // ─────────────────────────────────────────────────────────────────────────
    private void processFilter(User user,
                               UserSavedFilter filter,
                               List<BidDetails> activeBids,
                               Set<Long> alreadyMatched,
                               List<MatchedBids> toSave) {

        String categoryName = resolvedCategoryName(filter.getCategoryId());
        if (categoryName == null) return;

        List<Long> filterStateIds = parseIds(filter.getStateIds());
        List<Long> filterCityIds  = parseIds(filter.getCityIds());

        int newForFilter = 0;
        int dupForFilter = 0;

        for (BidDetails details : activeBids) {

            // details.getBid() is safe — JOIN FETCH in the repository pre-loaded it
            Long bidId = details.getBid().getId();

            if (alreadyMatched.contains(bidId)) {
                dupForFilter++;
                totalDuplicates.incrementAndGet();
                continue;
            }

            if (!matchesAllCriteria(details, categoryName, filterStateIds, filterCityIds)) continue;

            MatchedBids m = new MatchedBids();
            m.setUserId(user.getId());
            m.setBidId(bidId);
            m.setFilterId(filter.getId());
            m.setCategoryId(filter.getCategoryId());
            m.setIsViewed(false);
            toSave.add(m);

            // Add to in-memory set so the next filter in the same run
            // doesn't try to insert a duplicate for the same user+bid pair
            alreadyMatched.add(bidId);
            newForFilter++;
            totalMatchesFound.incrementAndGet();
            totalNewMatches.incrementAndGet();
        }

        if (newForFilter > 0) {
            log.info("   User {} filter '{}' [cat='{}' states={} cities={}]: {} new  {} dup",
                    user.getId(), filter.getFilterName(), categoryName,
                    filterStateIds.size(), filterCityIds.size(),
                    newForFilter, dupForFilter);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  CORE MATCHING LOGIC
    //
    //  A bid matches a filter when ALL THREE criteria are satisfied:
    //
    //  1. CATEGORY  — bid's pdf_content contains the category name
    //                 (exact phrase, case-insensitive, word-boundary aware)
    //
    //  2. STATE     — if filter.stateIds is non-empty, bid's state.id must be
    //                 in that list.  Empty list = "any state" (no restriction).
    //
    //  3. CITY      — if filter.cityIds is non-empty, bid's consigneeCity.id
    //                 must be in that list. Empty list = "any city".
    //
    //  BidDetails entity fields used:
    //    details.getPdfContent()               — LONGTEXT column, already there
    //    details.getBid().getId()              — via @OneToOne JOIN FETCH
    //    details.getBid().getState()           — via @OneToOne JOIN FETCH
    //    details.getBid().getConsigneeCity()   — via @OneToOne JOIN FETCH
    // ═══════════════════════════════════════════════════════════════════════════
    private boolean matchesAllCriteria(BidDetails details,
                                       String categoryName,
                                       List<Long> filterStateIds,
                                       List<Long> filterCityIds) {

        // ── 1. Category must appear in pdf_content ───────────────────────────
        if (!categoryInContent(details.getPdfContent(), categoryName)) return false;

        // ── 2. State filter ──────────────────────────────────────────────────
        // Empty filterStateIds means "all states" — skip this check.
        if (!filterStateIds.isEmpty()) {
            var state = details.getBid().getState();
            if (state == null) return false;
            if (!filterStateIds.contains(state.getId())) return false;
        }

        // ── 3. City filter ───────────────────────────────────────────────────
        // Empty filterCityIds means "all cities" — skip this check.
        if (!filterCityIds.isEmpty()) {
            var city = details.getBid().getConsigneeCity();
            if (city == null) return false;
            if (!filterCityIds.contains(city.getId())) return false;
        }

        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CATEGORY TEXT MATCHING
    //
    //  Primary  : full phrase, case-insensitive, word-boundary check.
    //             "computer" will NOT match "computerised".
    //
    //  Secondary: every significant keyword must be present (100%, not 70%).
    //             Only used when phrase ≥ 2 significant words.
    //             "Office Furniture" requires BOTH "office" AND "furniture".
    //             Stop words (and, the, for, ...) are excluded from the count.
    // ─────────────────────────────────────────────────────────────────────────
    private boolean categoryInContent(String pdfContent, String categoryName) {
        if (pdfContent == null || pdfContent.isBlank()) return false;
        if (categoryName == null || categoryName.isBlank()) return false;

        String content = pdfContent.toLowerCase();
        String term    = categoryName.toLowerCase().trim();

        // Primary: exact phrase with word-boundary check
        if (containsWholePhrase(content, term)) return true;

        // Secondary: all significant words present
        String[] words = term.split("\\s+");
        String[] significant = Arrays.stream(words)
                .filter(w -> w.length() > 3 && !STOP_WORDS.contains(w))
                .toArray(String[]::new);

        // Single-word categories must match via primary only
        if (significant.length < 2) return false;

        // ALL significant words required (100% — not 70%)
        for (String kw : significant) {
            if (!content.contains(kw)) return false;
        }
        return true;
    }

    /**
     * Returns true only when {@code phrase} appears in {@code content} as a
     * complete token — not embedded inside a longer word.
     *
     * Example:  phrase = "computer"
     *   "buy computer hardware"   → true   (spaces on both sides)
     *   "computerised system"     → false  (letter immediately after)
     */
    private boolean containsWholePhrase(String content, String phrase) {
        int idx = content.indexOf(phrase);
        while (idx != -1) {
            boolean startOk = (idx == 0)
                    || !Character.isLetterOrDigit(content.charAt(idx - 1));
            boolean endOk = (idx + phrase.length() >= content.length())
                    || !Character.isLetterOrDigit(content.charAt(idx + phrase.length()));
            if (startOk && endOk) return true;
            idx = content.indexOf(phrase, idx + 1);
        }
        return false;
    }

    // Common English stop words — excluded from the keyword-presence check
    private static final Set<String> STOP_WORDS = Set.of(
            "and", "the", "for", "with", "from", "that", "this", "are",
            "was", "has", "have", "not", "but", "its", "into", "over",
            "also", "each", "than", "then", "when", "which", "where",
            "such", "more", "other", "only", "some", "what", "just"
    );

    // ═══════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Parse "[1,2,3]" stored in UserSavedFilter.stateIds / cityIds into a List.
     * Returns empty list on null, blank, or malformed JSON — treated as "no filter".
     */
    private List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("Could not parse ID list from '{}': {}", json, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Returns the category name from cache. Refreshes the cache once if the
     * category is missing (handles categories added after startup).
     * Returns null if the category genuinely does not exist.
     */
    private String resolvedCategoryName(Long categoryId) {
        if (categoryId == null) return null;
        String name = categoryNameCache.get(categoryId);
        if (name == null) {
            loadCategoryCache();
            name = categoryNameCache.get(categoryId);
        }
        return (name == null || name.isBlank()) ? null : name;
    }

    private void resetCounters() {
        totalUsersProcessed.set(0);
        totalFiltersProcessed.set(0);
        totalBidsScanned.set(0);
        totalMatchesFound.set(0);
        totalNewMatches.set(0);
        totalDuplicates.set(0);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════
    public Map<String, Object> getStats() {
        Map<String, Object> s = new HashMap<>();
        s.put("isRunning",             isRunning.get());
        s.put("lastRunTime",           lastRunTime);
        s.put("totalRuns",             totalRuns.get());
        s.put("totalUsersProcessed",   totalUsersProcessed.get());
        s.put("totalFiltersProcessed", totalFiltersProcessed.get());
        s.put("totalBidsScanned",      totalBidsScanned.get());
        s.put("totalMatchesFound",     totalMatchesFound.get());
        s.put("totalNewMatches",       totalNewMatches.get());
        s.put("totalDuplicates",       totalDuplicates.get());
        s.put("totalStoredMatches",    matchedBidsRepository.count());
        return s;
    }

    public void manuallyRunMatching() {
        log.info("👤 Manual match job triggered");
        runMatchingJob();
    }
}