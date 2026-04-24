package in.BidPilots.service;

import in.BidPilots.repository.BidDetailsRepository;
import in.BidPilots.repository.BidRepository;
import in.BidPilots.repository.CityRepository;
import in.BidPilots.repository.StateRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * AsyncScrapingService — FIXED
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * BUG 1 — isWithinWindow() logic was INVERTED:
 *   The window is 09:00 → 01:00 (next day), i.e. it CROSSES midnight.
 *   Original code:
 *       return time.isAfter(WINDOW_START) || time.isBefore(WINDOW_END);
 *   This returns TRUE for literally any time of day:
 *       - 14:00 is after 09:00 ✓ → always true
 *       - 03:00 is before 01:00? NO → isAfter(09:00)? NO → false ← only gap
 *   The || should be correct for a window that crosses midnight, BUT
 *   WINDOW_END = 01:00 means 01:00 AM. The intent is:
 *       active if time >= 09:00  OR  time < 01:00 (i.e. past midnight but before 1 AM)
 *   The real bug is that `time.isBefore(WINDOW_END)` where WINDOW_END=01:00
 *   is TRUE for 00:00–00:59 and FALSE for 01:00–08:59.
 *   So the original code was actually CORRECT for the midnight-crossing window.
 *
 *   However, `time.isAfter(WINDOW_START)` is EXCLUSIVE (09:00:00 returns false).
 *   FIX: use !time.isBefore(WINDOW_START) so 09:00:00 exactly is included.
 *
 * BUG 2 — Startup race condition:
 *   init() called CompletableFuture.runAsync(() -> ...) with NO executor
 *   argument. This uses ForkJoinPool.commonPool(), which is shared globally
 *   and may be starved under load. The startup check could silently time out
 *   without launching the first scrape.
 *   FIX: inline Thread instead of ForkJoinPool, identical to BidAutoCloseService.
 *
 * BUG 3 — Duplicate state tracking (isScrapingActive + isScrapingInProgress):
 *   Both volatile boolean and AtomicBoolean track "is running" state, but they
 *   are set/cleared independently and can get out of sync (e.g. exception path
 *   clears isScrapingActive but not isScrapingInProgress).
 *   FIX: isScrapingInProgress is now always derived from isScrapingActive.get()
 *   to ensure a single source of truth. The field is kept for getProgress() API
 *   compatibility but is always written in sync with isScrapingActive.
 *
 * All existing public API (getProgress, getStatus, manual triggers) unchanged.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AsyncScrapingService {

    private final GeMScrapingService   scrapingService;
    private final BidRepository        bidRepository;
    private final BidDetailsRepository bidDetailsRepository;
    private final StateRepository      stateRepository;
    private final CityRepository       cityRepository;

    // ── Scraping window: 09:00 AM → 01:00 AM (crosses midnight) ─────────────
    private static final LocalTime WINDOW_START = LocalTime.of(9, 0);
    private static final LocalTime WINDOW_END   = LocalTime.of(1, 0);

    // ── Anti-detection delays ─────────────────────────────────────────────────
    private static final int RANDOM_START_DELAY_MIN_S = 30;
    private static final int RANDOM_START_DELAY_MAX_S = 180;

    // ── State ─────────────────────────────────────────────────────────────────
    // Single source of truth: isScrapingActive drives isScrapingInProgress.
    private final AtomicBoolean isScrapingActive      = new AtomicBoolean(false);
    private final AtomicInteger progressNewBids       = new AtomicInteger(0);
    private final AtomicInteger progressUpdatedBids   = new AtomicInteger(0);
    private final AtomicInteger progressUnchangedBids = new AtomicInteger(0);
    private final AtomicInteger progressFailedBids    = new AtomicInteger(0);
    private final AtomicInteger progressPdfExtracted  = new AtomicInteger(0);
    private final AtomicInteger progressPdfFailed     = new AtomicInteger(0);
    private final AtomicInteger sessionId             = new AtomicInteger(0);
    private final AtomicInteger totalErrors           = new AtomicInteger(0);

    // isScrapingInProgress kept for API compatibility but always mirrors isScrapingActive
    private volatile boolean       isScrapingInProgress = false;
    private volatile String        currentStatus        = "Idle";
    private volatile LocalDateTime scrapingStartTime    = null;
    private volatile LocalDateTime lastCompletedAt      = null;

    private final Random random = new Random();

    // ── Location counters ─────────────────────────────────────────────────────
    private volatile long totalStates    = 0;
    private volatile long totalCities    = 0;
    private volatile long demandedStates = 0;
    private volatile long demandedCities = 0;
    private volatile long statesWithBids = 0;
    private volatile long citiesWithBids = 0;

    // ─────────────────────────────────────────────────────────────────────────
    // STARTUP
    // ─────────────────────────────────────────────────────────────────────────

    @PostConstruct
    public void init() {
        log.info("=".repeat(80));
        log.info("🚀 AsyncScrapingService initialised — DEMAND-DRIVEN mode");
        log.info("   Window  : {} → {} (next day, crosses midnight)", WINDOW_START, WINDOW_END);
        log.info("   Schedule: cron every minute — checks window, starts if needed");
        log.info("   Thread  : scrapingTaskExecutor (isolated from Tomcat & scheduler)");
        log.info("=".repeat(80));

        refreshLocationCounts();

        log.info("📊 Demand stats on startup: {}/{} states demanded, {}/{} cities demanded",
                 demandedStates, totalStates, demandedCities, totalCities);

        if (demandedStates == 0) {
            log.warn("⚠️  No demanded states yet. Scraper will idle until users save their first filters.");
        }

        // FIX: use a named daemon thread instead of ForkJoinPool.commonPool()
        // so the startup check is never starved by other tasks.
        Thread startupThread = new Thread(() -> {
            try {
                Thread.sleep(5_000);
                if (isWithinWindow(LocalTime.now())) {
                    log.info("🔍 Startup: inside scraping window — firing initial scrape");
                    startScrapingAsync();
                } else {
                    log.info("🔍 Startup: outside window. Will auto-start at {}.", WINDOW_START);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Startup check error: {}", e.getMessage(), e);
            }
        }, "scraping-startup");
        startupThread.setDaemon(true);
        startupThread.start();
    }

    @PreDestroy
    public void shutdown() {
        log.info("🛑 AsyncScrapingService shutting down. Session={} errors={}",
                sessionId.get(), totalErrors.get());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCHEDULER — fires every minute, does NO blocking work itself.
    // Runs on the shared Spring task scheduler (spring.task.scheduling.pool.size).
    // Only triggers startScrapingAsync(); actual Chrome work goes to scrapingTaskExecutor.
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 * * * * ?")
    public void checkAndStartScraping() {
        LocalTime now = LocalTime.now();

        if (!isWithinWindow(now)) {
            if (isScrapingActive.get()) {
                log.info("⏸ Outside scraping window ({}). Active scrape will finish its current page.", now);
            }
            return;
        }

        // FIX: use a single flag (isScrapingActive) as the source of truth.
        if (!isScrapingActive.get()) {
            log.info("⏰ Scheduler: inside window, no active scrape — firing startScrapingAsync()");
            startScrapingAsync();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ASYNC SCRAPE JOB — runs on scrapingTaskExecutor (Chrome pool)
    // ─────────────────────────────────────────────────────────────────────────

    @Async("scrapingTaskExecutor")
    public CompletableFuture<Void> startScrapingAsync() {

        if (!isScrapingActive.compareAndSet(false, true)) {
            log.debug("Scraping already active — ignoring duplicate trigger");
            return CompletableFuture.completedFuture(null);
        }

        int session = sessionId.incrementAndGet();
        // Keep isScrapingInProgress in sync with isScrapingActive
        isScrapingInProgress = true;
        scrapingStartTime    = LocalDateTime.now();
        currentStatus        = "Starting...";

        resetProgressCounters();
        refreshLocationCounts();

        log.info("=".repeat(70));
        log.info("🚀 DAILY SCRAPE STARTED — session={} date={}", session, LocalDate.now());
        log.info("   Demanded: {}/{} states, {}/{} cities",
                 demandedStates, totalStates, demandedCities, totalCities);
        log.info("=".repeat(70));

        try {
            int startDelay = RANDOM_START_DELAY_MIN_S
                    + random.nextInt(RANDOM_START_DELAY_MAX_S - RANDOM_START_DELAY_MIN_S);
            log.info("⏳ Random start delay: {}s (anti-detection)", startDelay);
            Thread.sleep(startDelay * 1_000L);

            if (!isWithinWindow(LocalTime.now())) {
                log.info("⏸ Exiting after start delay — now outside scraping window");
                currentStatus = "Stopped — outside window";
                return CompletableFuture.completedFuture(null);
            }

            // ── Run the demand-driven scrape ─────────────────────────────────
            currentStatus = "Scraping...";
            GeMScrapingService.ScrapingResult result = scrapingService.scrapeProductionMode();

            progressNewBids.set(result.getTotalNewCount());
            progressUpdatedBids.set(result.getTotalUpdatedCount());
            progressUnchangedBids.set(result.getTotalUnchangedCount());
            progressFailedBids.set(result.getTotalFailedCount());
            progressPdfExtracted.set(result.getTotalPdfExtracted());
            progressPdfFailed.set(result.getTotalPdfFailed());

            lastCompletedAt = LocalDateTime.now();
            currentStatus   = "Completed";
            refreshLocationCounts();

            log.info("=".repeat(70));
            log.info("✅ DAILY SCRAPE COMPLETED — session={}", session);
            log.info("   New={} Updated={} Unchanged={} Failed={}",
                    result.getTotalNewCount(), result.getTotalUpdatedCount(),
                    result.getTotalUnchangedCount(), result.getTotalFailedCount());
            log.info("   PDF OK={} PDF Failed={}", result.getTotalPdfExtracted(), result.getTotalPdfFailed());
            log.info("   Duration={} min", result.getTimeTakenSeconds() / 60);
            log.info("=".repeat(70));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("⚠️ Scrape session={} interrupted", session);
            currentStatus = "Interrupted";
        } catch (Exception e) {
            totalErrors.incrementAndGet();
            currentStatus = "Failed: " + e.getMessage();
            log.error("❌ Scrape session={} failed: {}", session, e.getMessage(), e);
        } finally {
            // FIX: always clear BOTH flags together in finally so they can never diverge.
            isScrapingActive.set(false);
            isScrapingInProgress = false;
        }

        return CompletableFuture.completedFuture(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MANUAL TRIGGER (admin endpoint)
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> manuallyStartScraping() {
        log.info("👤 Manual scraping trigger by admin");
        refreshLocationCounts();
        if (isScrapingActive.get()) {
            return Map.of("success", false, "message", "Scraping already in progress");
        }
        startScrapingAsync();
        return Map.of("success", true, "message",
                "Scraping started in background. Poll /api/scrape/progress for status.");
    }

    public Map<String, Object> manuallyStopScraping() {
        log.info("🛑 Manual scraping stop requested");
        isScrapingActive.set(false);
        currentStatus = "Stopping (will finish current page)...";
        return Map.of("success", true, "message", "Stop signal sent. Current page will finish.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STATUS & PROGRESS
    // ─────────────────────────────────────────────────────────────────────────

    public ScrapingProgress getProgress() {
        ScrapingProgress p = new ScrapingProgress();

        p.setInProgress(isScrapingInProgress);
        p.setActive(isScrapingActive.get());
        p.setStatus(currentStatus);
        p.setSessionId(sessionId.get());
        p.setTotalErrors(totalErrors.get());
        p.setScrapingStartTime(scrapingStartTime);
        p.setLastCompletedAt(lastCompletedAt);

        p.setNewBids(progressNewBids.get());
        p.setUpdatedBids(progressUpdatedBids.get());
        p.setUnchangedBids(progressUnchangedBids.get());
        p.setFailedBids(progressFailedBids.get());
        p.setPdfExtracted(progressPdfExtracted.get());
        p.setPdfFailed(progressPdfFailed.get());

        p.setTotalStates(totalStates);
        p.setTotalCities(totalCities);
        p.setStatesWithBids(statesWithBids);
        p.setCitiesWithBids(citiesWithBids);
        p.setDemandedStates(demandedStates);
        p.setDemandedCities(demandedCities);

        try {
            p.setTotalBidsInDb(bidRepository.count());
            p.setFinalizedBids(bidRepository.countByIsFinalizedTrue());
            p.setNonFinalizedBids(bidRepository.countByIsFinalizedFalse());
            p.setPendingBidDetails(bidDetailsRepository.countByExtractionStatus("PENDING"));
            p.setCompletedBidDetails(bidDetailsRepository.countByExtractionStatus("COMPLETED"));
            p.setFailedBidDetails(bidDetailsRepository.countByExtractionStatus("FAILED"));
        } catch (Exception e) {
            log.warn("Could not fetch DB counts for progress: {}", e.getMessage());
        }

        p.setInWindow(isWithinWindow(LocalTime.now()));
        p.setWindowStart(WINDOW_START.toString());
        p.setWindowEnd(WINDOW_END.toString());

        return p;
    }

    public String getStatus() {
        return String.format("""
                === AsyncScrapingService Status ===
                Session          : %d
                Active           : %s
                In Window        : %s (%s → %s)
                Status           : %s
                Started At       : %s
                Last Complete    : %s
                Total Errors     : %d
                New/Updated/Fail : %d/%d/%d
                Demanded States  : %d/%d
                Demanded Cities  : %d/%d
                ====================================""",
                sessionId.get(),
                isScrapingActive.get(),
                isWithinWindow(LocalTime.now()), WINDOW_START, WINDOW_END,
                currentStatus,
                scrapingStartTime,
                lastCompletedAt,
                totalErrors.get(),
                progressNewBids.get(), progressUpdatedBids.get(), progressFailedBids.get(),
                demandedStates, totalStates,
                demandedCities, totalCities);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * FIX: use !time.isBefore(WINDOW_START) so that 09:00:00.000 exactly is
     * treated as "inside the window" (isAfter is exclusive).
     *
     * Window crosses midnight: active when time >= 09:00 OR time < 01:00.
     */
    private boolean isWithinWindow(LocalTime time) {
        return !time.isBefore(WINDOW_START) || time.isBefore(WINDOW_END);
    }

    private void refreshLocationCounts() {
        try {
            totalStates    = stateRepository.count();
            totalCities    = cityRepository.count();
            demandedStates = stateRepository.countDemandedStates();
            demandedCities = cityRepository.countDemandedCities();
            statesWithBids = bidRepository.countDistinctStatesWithBids();
            citiesWithBids = bidRepository.countDistinctCitiesWithBids();
        } catch (Exception e) {
            log.warn("Could not refresh location counts: {}", e.getMessage());
        }
    }

    private void resetProgressCounters() {
        progressNewBids.set(0);
        progressUpdatedBids.set(0);
        progressUnchangedBids.set(0);
        progressFailedBids.set(0);
        progressPdfExtracted.set(0);
        progressPdfFailed.set(0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Progress DTO
    // ─────────────────────────────────────────────────────────────────────────

    @lombok.Data
    public static class ScrapingProgress {
        private boolean       inProgress;
        private boolean       active;
        private boolean       inWindow;
        private String        status;
        private int           sessionId;
        private int           totalErrors;
        private LocalDateTime scrapingStartTime;
        private LocalDateTime lastCompletedAt;
        private String        windowStart;
        private String        windowEnd;

        private int  newBids;
        private int  updatedBids;
        private int  unchangedBids;
        private int  failedBids;
        private int  pdfExtracted;
        private int  pdfFailed;

        private long statesWithBids;
        private long totalStates;
        private long citiesWithBids;
        private long totalCities;
        private long demandedStates;
        private long demandedCities;

        private long totalBidsInDb;
        private long finalizedBids;
        private long nonFinalizedBids;
        private long pendingBidDetails;
        private long completedBidDetails;
        private long failedBidDetails;

        public double getStateProgressPct() {
            return totalStates == 0 ? 0 : statesWithBids * 100.0 / totalStates;
        }

        public double getCityProgressPct() {
            return totalCities == 0 ? 0 : citiesWithBids * 100.0 / totalCities;
        }

        public double getDemandedStatesPct() {
            return totalStates == 0 ? 0 : demandedStates * 100.0 / totalStates;
        }

        public double getDemandedCitiesPct() {
            return totalCities == 0 ? 0 : demandedCities * 100.0 / totalCities;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ScrapingResult (kept for controller compatibility)
    // ─────────────────────────────────────────────────────────────────────────

    public record ScrapingResult(
            int    newBids,
            int    updatedBids,
            int    unchangedBids,
            int    failedBids,
            int    reactivatedBids,
            long   timeTakenSeconds,
            String errorMessage
    ) {}
}