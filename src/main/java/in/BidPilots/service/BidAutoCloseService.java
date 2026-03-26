package in.BidPilots.service;

import in.BidPilots.entity.Bid;
import in.BidPilots.repository.BidRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class BidAutoCloseService {

    private final BidRepository bidRepository;
    
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private final AtomicBoolean isFinalizing = new AtomicBoolean(false);
    
    // Batch size for processing
    private static final int BATCH_SIZE = 1000;
    private static final int FINALIZE_BATCH_SIZE = 5000;
    
    // Statistics counters
    private final AtomicInteger totalClosedToday = new AtomicInteger(0);
    private final AtomicInteger totalFinalizedToday = new AtomicInteger(0);
    private LocalDateTime lastScanTime = LocalDateTime.now();
    private LocalDateTime lastFinalizeTime = LocalDateTime.now();
    
    @PostConstruct
    public void init() {
        log.info("=".repeat(80));
        log.info("🚀 AUTO BID CLOSING & FINALIZATION SERVICE ACTIVATED");
        log.info("=".repeat(80));
        log.info("   ✅ Bid Closing: Sets isActive=false when bid end date passes");
        log.info("   ✅ Bid Finalization: Sets isFinalized=true when bid_end_date is 3+ days old");
        log.info("   ✅ Batch Processing: {} bids at a time", BATCH_SIZE);
        log.info("   ✅ Finalization Batch Size: {} bids", FINALIZE_BATCH_SIZE);
        log.info("   ✅ Scan Interval: 1 second (for BOTH closing and finalization)");
        log.info("   ✅ Atomic Flags to prevent concurrent scans");
        log.info("   ✅ Initial Cleanup on Startup");
        log.info("=".repeat(80));
        
        // First, close all expired bids on startup (set isActive=false)
        int closedCount = closeAllExpiredBidsInBatches();
        log.info("✅ Initial cleanup closed {} expired bids", closedCount);
        totalClosedToday.addAndGet(closedCount);
        
        // Finalize bids that ended 3+ days ago (set isFinalized=true)
        int finalizedCount = finalizeOldBidsNow();
        log.info("✅ Initial finalization marked {} bids as finalized (ended 3+ days ago)", finalizedCount);
        totalFinalizedToday.addAndGet(finalizedCount);
        
        // Start optimized monitor that runs BOTH closing and finalization every second
        startOptimizedMonitor();
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("🛑 Shutting down Auto Bid Closing & Finalization Service...");
        log.info("   Total bids closed today: {}", totalClosedToday.get());
        log.info("   Total bids finalized today: {}", totalFinalizedToday.get());
        
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("✅ Auto Bid Closing & Finalization Service stopped");
    }
    
    /**
     * Start optimized monitoring - scans every 1 second for BOTH closing and finalization
     */
    private void startOptimizedMonitor() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BidAutoClose-Optimized");
            t.setDaemon(true);
            return t;
        });
        
        // Run every 1 second - does both closing and finalization
        scheduler.scheduleAtFixedRate(() -> {
            scanAndCloseInBatches();        // Close expired bids
            finalizeOldBidsInBatches();     // Finalize bids ended 3+ days ago
        }, 1, 1, TimeUnit.SECONDS);
        
        log.info("✅ Optimized monitor active - scanning every 1 second for closing AND finalization");
    }
    
    /**
     * Scan and close expired bids ONLY (set isActive=false, isDeactive=true)
     */
    @Transactional
    public void scanAndCloseInBatches() {
        // Prevent concurrent scans
        if (!isScanning.compareAndSet(false, true)) {
            log.debug("Previous scan still in progress, skipping this cycle");
            return;
        }
        
        try {
            LocalDateTime now = LocalDateTime.now();
            lastScanTime = now;
            
            int totalClosed = 0;
            int pageNumber = 0;
            boolean hasMore = true;
            
            while (hasMore && !Thread.currentThread().isInterrupted()) {
                Pageable pageable = PageRequest.of(pageNumber, BATCH_SIZE);
                
                // Get next batch of expired bids (where bidEndDate <= now and isActive = true)
                List<Bid> expiredBatch = bidRepository.findExpiredBidsBatch(now, pageable);
                
                if (expiredBatch.isEmpty()) {
                    hasMore = false;
                } else {
                    // Process the batch - set isActive=false and isDeactive=true
                    for (Bid bid : expiredBatch) {
                        if (bid.getBidEndDate() != null && !bid.getBidEndDate().isAfter(now)) {
                            bid.setIsActive(false);
                            bid.setIsDeactive(true);
                            log.debug("Closing bid: {} (ended: {})", bid.getBidNumber(), bid.getBidEndDate());
                        }
                    }
                    
                    // Save the entire batch
                    bidRepository.saveAll(expiredBatch);
                    bidRepository.flush();
                    
                    int batchSize = expiredBatch.size();
                    totalClosed += batchSize;
                    
                    log.info("   Closing Batch #{}: Closed {} bids (Total so far: {})", 
                             pageNumber + 1, batchSize, totalClosed);
                    
                    pageNumber++;
                    
                    if (hasMore) {
                        Thread.sleep(50);
                    }
                }
            }
            
            if (totalClosed > 0) {
                totalClosedToday.addAndGet(totalClosed);
                log.info("✅ Closing scan completed: Closed {} expired bids in {} batches", 
                         totalClosed, pageNumber);
                log.info("   Total closed today: {}", totalClosedToday.get());
            }
            
        } catch (InterruptedException e) {
            log.warn("Batch scan interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Error in batch scan: {}", e.getMessage(), e);
        } finally {
            isScanning.set(false);
        }
    }
    
    /**
     * FIXED: Finalize bids that ended 3 or more days ago
     * Runs every second to ensure isFinalized is set correctly
     */
    @Transactional
    public int finalizeOldBidsNow() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threeDaysAgo = now.minusDays(3);
        
        log.info("🔍 Starting bulk finalization of bids that ended 3+ days ago");
        log.info("   Current time: {}, Bids ended before: {}", now, threeDaysAgo);
        
        // Count how many will be finalized (bids with end date <= 3 days ago AND not finalized)
        long countToFinalize = bidRepository.countBidsToFinalize(threeDaysAgo);
        
        if (countToFinalize == 0) {
            return 0;
        }
        
        log.info("   Found {} bids that ended 3+ days ago and are not finalized", countToFinalize);
        
        // Perform bulk update - set isFinalized=true for bids ended 3+ days ago
        int finalizedCount = bidRepository.bulkFinalizeOldBidsByEndDate(threeDaysAgo);
        
        log.info("✅ Bulk finalization completed: {} bids finalized (ended 3+ days ago)", finalizedCount);
        lastFinalizeTime = now;
        
        return finalizedCount;
    }
    
    /**
     * Batch finalization for bids that ended 3+ days ago - runs every second
     */
    @Transactional
    public int finalizeOldBidsInBatches() {
        if (!isFinalizing.compareAndSet(false, true)) {
            return 0;
        }
        
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime threeDaysAgo = now.minusDays(3);
            
            int totalFinalized = 0;
            int pageNumber = 0;
            boolean hasMore = true;
            
            while (hasMore && !Thread.currentThread().isInterrupted()) {
                Pageable pageable = PageRequest.of(pageNumber, FINALIZE_BATCH_SIZE);
                
                // Get bids that ended 3+ days ago and are not finalized
                List<Bid> batch = bidRepository.findBidsToFinalizeBatch(threeDaysAgo, pageable);
                
                if (batch.isEmpty()) {
                    hasMore = false;
                } else {
                    for (Bid bid : batch) {
                        bid.setIsFinalized(true);
                        log.debug("Finalizing bid: {} (ended: {})", bid.getBidNumber(), bid.getBidEndDate());
                    }
                    
                    bidRepository.saveAll(batch);
                    bidRepository.flush();
                    
                    totalFinalized += batch.size();
                    pageNumber++;
                    
                    log.info("   Finalize Batch #{}: Finalized {} bids (ended 3+ days ago) - Total so far: {}", 
                             pageNumber, batch.size(), totalFinalized);
                    
                    if (hasMore) {
                        Thread.sleep(100);
                    }
                }
            }
            
            if (totalFinalized > 0) {
                totalFinalizedToday.addAndGet(totalFinalized);
                log.info("✅ Finalization scan completed: {} bids finalized in {} batches", 
                         totalFinalized, pageNumber);
                lastFinalizeTime = now;
            }
            
            return totalFinalized;
            
        } catch (InterruptedException e) {
            log.warn("Batch finalization interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            log.error("Error in batch finalization: {}", e.getMessage(), e);
            return 0;
        } finally {
            isFinalizing.set(false);
        }
    }
    
    /**
     * Initial cleanup - close ALL expired bids on startup
     */
    @Transactional
    public int closeAllExpiredBidsInBatches() {
        LocalDateTime now = LocalDateTime.now();
        int totalClosed = 0;
        int pageNumber = 0;
        boolean hasMore = true;
        
        log.info("🔍 Starting INITIAL CLEANUP of expired bids...");
        log.info("   Current time: {}", now);
        
        while (hasMore) {
            Pageable pageable = PageRequest.of(pageNumber, BATCH_SIZE);
            List<Bid> expiredBatch = bidRepository.findExpiredBidsBatch(now, pageable);
            
            if (expiredBatch.isEmpty()) {
                hasMore = false;
                log.info("   Initial cleanup completed after {} batches", pageNumber);
            } else {
                for (Bid bid : expiredBatch) {
                    if (bid.getBidEndDate() != null && !bid.getBidEndDate().isAfter(now)) {
                        bid.setIsActive(false);
                        bid.setIsDeactive(true);
                    }
                }
                
                bidRepository.saveAll(expiredBatch);
                bidRepository.flush();
                
                int batchSize = expiredBatch.size();
                totalClosed += batchSize;
                
                log.info("   Initial Batch #{}: Closed {} bids (Running total: {})", 
                         pageNumber + 1, batchSize, totalClosed);
                
                pageNumber++;
                
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        if (totalClosed > 0) {
            log.info("✅ INITIAL CLEANUP COMPLETE: Closed {} expired bids", totalClosed);
        } else {
            log.info("✅ INITIAL CLEANUP: No expired bids found");
        }
        
        return totalClosed;
    }
    
    /**
     * Manual trigger for closing expired bids
     */
    @Transactional
    public int manuallyCloseExpiredBids() {
        log.info("🔍 Manual trigger: Starting batch close...");
        int beforeCount = totalClosedToday.get();
        scanAndCloseInBatches();
        int afterCount = totalClosedToday.get();
        int closed = afterCount - beforeCount;
        log.info("✅ Manual close completed: {} bids closed", closed);
        return closed;
    }
    
    /**
     * Manual trigger for finalizing bids ended 3+ days ago
     */
    @Transactional
    public int manuallyFinalizeOldBids() {
        log.info("🔍 Manual trigger: Starting bid finalization (3+ day old bids)...");
        int finalizedCount = finalizeOldBidsNow();
        log.info("✅ Manual finalization completed: {} bids finalized (ended 3+ days ago)", finalizedCount);
        return finalizedCount;
    }
    
    /**
     * Get service statistics
     */
    public String getStats() {
        LocalDateTime threeDaysAgo = LocalDateTime.now().minusDays(3);
        long pendingFinalization = bidRepository.countBidsToFinalize(threeDaysAgo);
        
        return String.format("""
                \n=== Bid Auto Close & Finalization Service Stats ===
                Total closed today (active->inactive): %d
                Total finalized today (3+ day old bids): %d
                Last scan time: %s
                Last finalize time: %s
                Is scanning: %s
                Is finalizing: %s
                Pending finalization (ended 3+ days ago): %d
                Close batch size: %d
                Finalize batch size: %d
                ===================================================""",
                totalClosedToday.get(),
                totalFinalizedToday.get(),
                lastScanTime,
                lastFinalizeTime,
                isScanning.get(),
                isFinalizing.get(),
                pendingFinalization,
                BATCH_SIZE,
                FINALIZE_BATCH_SIZE);
    }
    
    /**
     * Check if there are any expired bids without scanning
     */
    @Transactional(readOnly = true)
    public long countExpiredBids() {
        return bidRepository.countExpiredBids(LocalDateTime.now());
    }
    
    /**
     * Check how many bids are pending finalization (ended 3+ days ago)
     */
    @Transactional(readOnly = true)
    public long countPendingFinalization() {
        LocalDateTime threeDaysAgo = LocalDateTime.now().minusDays(3);
        return bidRepository.countBidsToFinalize(threeDaysAgo);
    }
}