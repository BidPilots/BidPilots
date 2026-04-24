package in.BidPilots.service;

import in.BidPilots.entity.Bid;
import in.BidPilots.repository.BidRepository;
import in.BidPilots.service.BidDetailsService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PRODUCTION FIX — BidAutoCloseService
 *
 * ORIGINAL PROBLEM:
 *   The service created its own ScheduledExecutorService running every 1 SECOND,
 *   issuing DB batch-reads and writes constantly. This generated enormous DB load
 *   and competed with every user-facing query (login, registration, bids, etc.).
 *
 * FIX:
 *   1. Removed the hand-rolled ScheduledExecutorService entirely.
 *      Spring's @Scheduled now drives execution — lifecycle is managed by Spring,
 *      errors are logged properly, and graceful shutdown is automatic.
 *
 *   2. Polling interval changed from 1 SECOND to 5 MINUTES (closing)
 *      and 1 HOUR (finalization). Bids that expire mid-minute are closed
 *      within 5 minutes — acceptable for a government tender platform.
 *      Finalization (3-day-old bids) has zero urgency; hourly is more than enough.
 *
 *   3. @PostConstruct still runs initial cleanup synchronously at startup,
 *      but uses a shorter sleep between batches (10 ms) to finish quickly.
 *
 *   4. @Scheduled methods are annotated with the Spring pool — they run on the
 *      shared Spring task scheduler thread, NOT on Tomcat worker threads.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BidAutoCloseService {

    private final BidRepository bidRepository;
    private final BidDetailsService bidDetailsService;

    private static final int BATCH_SIZE          = 500;
    private static final int FINALIZE_BATCH_SIZE = 2000;

    private final AtomicBoolean isClosing    = new AtomicBoolean(false);
    private final AtomicBoolean isFinalizing = new AtomicBoolean(false);

    private final AtomicInteger totalClosedToday    = new AtomicInteger(0);
    private final AtomicInteger totalFinalizedToday = new AtomicInteger(0);

    private volatile LocalDateTime lastCloseTime    = null;
    private volatile LocalDateTime lastFinalizeTime = null;

    // ── Startup: one-time catchup ─────────────────────────────────────────────
    @PostConstruct
    public void init() {
        log.info("=".repeat(70));
        log.info("🚀 BidAutoCloseService initialising — running startup catchup");
        log.info("   Close schedule  : every 5 minutes");
        log.info("   Finalize schedule: every 1 hour");
        log.info("=".repeat(70));

        // Run startup catchup in a background thread so startup is not delayed.
        Thread startup = new Thread(() -> {
            try {
                int closed = closeAllExpiredBidsInBatches();
                log.info("✅ Startup catchup: closed {} expired bids", closed);
                totalClosedToday.addAndGet(closed);

                int finalized = finalizeOldBidsNow();
                log.info("✅ Startup catchup: finalized {} old bids", finalized);
                totalFinalizedToday.addAndGet(finalized);
            } catch (Exception e) {
                log.error("Startup catchup error: {}", e.getMessage(), e);
            }
        }, "bid-close-startup");
        startup.setDaemon(true);
        startup.start();
    }

    // ── Scheduled: close expired bids every 5 minutes ────────────────────────
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 60 * 1000)
    public void scheduledClose() {
        if (!isClosing.compareAndSet(false, true)) {
            log.debug("Close scan already running — skipping tick");
            return;
        }
        try {
            int closed = closeAllExpiredBidsInBatches();
            if (closed > 0) {
                totalClosedToday.addAndGet(closed);
                log.info("⏱ Scheduled close: {} bids closed", closed);
            }
        } finally {
            isClosing.set(false);
        }
    }

    // ── Scheduled: finalize old bids every 1 hour ─────────────────────────────
    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void scheduledFinalize() {
        if (!isFinalizing.compareAndSet(false, true)) {
            log.debug("Finalize scan already running — skipping tick");
            return;
        }
        try {
            int finalized = finalizeOldBidsNow();
            if (finalized > 0) {
                totalFinalizedToday.addAndGet(finalized);
                log.info("⏱ Scheduled finalize: {} bids finalized", finalized);
            }
        } finally {
            isFinalizing.set(false);
        }
    }

    // ── Core: close all expired bids in batches ───────────────────────────────
    @Transactional
    public int closeAllExpiredBidsInBatches() {
        LocalDateTime now = LocalDateTime.now();
        int total = 0;
        int page  = 0;

        while (true) {
            Pageable pageable  = PageRequest.of(page, BATCH_SIZE);
            List<Bid> batch    = bidRepository.findExpiredBidsBatch(now, pageable);

            if (batch.isEmpty()) break;

            for (Bid bid : batch) {
                bid.setIsActive(false);
                bid.setIsDeactive(true);
            }
            bidRepository.saveAll(batch);
            bidRepository.flush();

            total += batch.size();
            page++;

            log.debug("Close batch {}: {} bids (total so far: {})", page, batch.size(), total);

            try { Thread.sleep(10); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (total > 0) {
            lastCloseTime = LocalDateTime.now();
            log.info("✅ closeExpired: {} bids closed in {} batches", total, page);
        }
        return total;
    }

    // ── Core: finalize bids ended 3+ days ago ────────────────────────────────
    @Transactional
    public int finalizeOldBidsNow() {
        LocalDateTime threeDaysAgo = LocalDateTime.now().minusDays(3);
        long pending = bidRepository.countBidsToFinalize(threeDaysAgo);
        if (pending == 0) return 0;

        int finalized = bidRepository.bulkFinalizeOldBidsByEndDate(threeDaysAgo);
        lastFinalizeTime = LocalDateTime.now();
        log.info("✅ finalize: {} bids finalized (ended 3+ days ago)", finalized);

        // Cleanup: delete bid_details rows for newly finalized bids.
        // PDF content, item-category cache and pre-bid dates are no longer
        // needed once a bid is permanently closed.
        int deleted = bidDetailsService.deleteDetailsForFinalizedBids();
        if (deleted > 0) {
            log.info("Cleaned up {} bid_details rows for finalized bids", deleted);
        }

        return finalized;
    }

    // ── Manual triggers (admin endpoints) ────────────────────────────────────
    public int manuallyCloseExpiredBids() {
        log.info("👤 Manual close triggered");
        return closeAllExpiredBidsInBatches();
    }

    public int manuallyFinalizeOldBids() {
        log.info("👤 Manual finalize triggered");
        return finalizeOldBidsNow();
    }

    // ── Stats ─────────────────────────────────────────────────────────────────
    public String getStats() {
        LocalDateTime threeDaysAgo = LocalDateTime.now().minusDays(3);
        long pending = bidRepository.countBidsToFinalize(threeDaysAgo);
        return String.format("""
                === BidAutoCloseService Stats ===
                Closed today      : %d
                Finalized today   : %d
                Last close        : %s
                Last finalize     : %s
                Is closing        : %s
                Is finalizing     : %s
                Pending finalize  : %d
                Close batch size  : %d
                Finalize batch    : %d
                ==================================""",
                totalClosedToday.get(), totalFinalizedToday.get(),
                lastCloseTime, lastFinalizeTime,
                isClosing.get(), isFinalizing.get(),
                pending, BATCH_SIZE, FINALIZE_BATCH_SIZE);
    }

    @Transactional(readOnly = true)
    public long countExpiredBids() {
        return bidRepository.countExpiredBids(LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public long countPendingFinalization() {
        return bidRepository.countBidsToFinalize(LocalDateTime.now().minusDays(3));
    }
}