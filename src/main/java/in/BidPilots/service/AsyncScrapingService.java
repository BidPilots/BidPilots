package in.BidPilots.service;

import in.BidPilots.repository.BidRepository;
import in.BidPilots.repository.BidDetailsRepository;
import in.BidPilots.repository.StateRepository;
import in.BidPilots.repository.CityRepository;
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
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class AsyncScrapingService {

	private final GeMScrapingService scrapingService;
	private final BidRepository bidRepository;
	private final BidDetailsRepository bidDetailsRepository;
	private final StateRepository stateRepository;
	private final CityRepository cityRepository;

	// Scraping window: 12:00 PM to 1:00 AM next day
	private static final LocalTime SCRAPING_START_TIME = LocalTime.of(1, 0); // 12:00 PM
	private static final LocalTime SCRAPING_END_TIME = LocalTime.of(1, 0); // 1:00 AM next day

	// ========== ANTI-DETECTION CONFIGURATION ==========
	// Human-like behavior simulation
	private static final int MIN_SCRAPE_DURATION_MINUTES = 45; // Minimum time to look human
	private static final int MAX_SCRAPE_DURATION_MINUTES = 120; // Maximum time before pause
	private static final int MIN_BATCH_SIZE = 2; // Min states to process before break
	private static final int MAX_BATCH_SIZE = 5; // Max states to process before break
	private static final int MIN_BREAK_MINUTES = 8; // Min break between batches (coffee break)
	private static final int MAX_BREAK_MINUTES = 20; // Max break between batches
	private static final int MIN_LONG_BREAK_MINUTES = 30; // Min long break (lunch break)
	private static final int MAX_LONG_BREAK_MINUTES = 60; // Max long break
	private static final int BATCHES_BEFORE_LONG_BREAK = 3; // Do long break after this many batches

	// Randomization factors
	private static final int RANDOM_START_DELAY_MIN = 30; // Random start delay in seconds
	private static final int RANDOM_START_DELAY_MAX = 300; // 30 seconds to 5 minutes
	private static final int RANDOM_PAGE_DELAY_MIN = 10; // Delay between pages in seconds
	private static final int RANDOM_PAGE_DELAY_MAX = 30; // 10-30 seconds between pages
	private static final int RANDOM_BID_DELAY_MIN = 3; // Delay between bids in seconds
	private static final int RANDOM_BID_DELAY_MAX = 8; // 3-8 seconds between bids

	// Weekend/holiday avoidance
	private static final boolean AVOID_WEEKENDS = true; // Don't scrape on weekends
	private static final boolean AVOID_PUBLIC_HOLIDAYS = true; // Don't scrape on holidays

	// Traffic pattern simulation
	private static final int PEAK_HOUR_START = 11; // 11 AM peak hour start
	private static final int PEAK_HOUR_END = 15; // 3 PM peak hour end
	private static final double PEAK_HOUR_INTENSITY = 0.8; // 80% intensity during peak
	private static final double OFF_PEAK_INTENSITY = 0.4; // 40% intensity off-peak

	// Error simulation (to look human/recover from blocks)
	private static final double ERROR_RATE = 0.02; // 2% error rate to simulate human mistakes
	private static final long RECOVERY_TIME_MIN = 60; // Recovery time in seconds after error
	private static final long RECOVERY_TIME_MAX = 300; // 1-5 minutes recovery

	// IP rotation simulation (if using proxies)
	private static final boolean SIMULATE_IP_ROTATION = true; // Simulate IP changes
	private static final int IP_ROTATION_INTERVAL_MINUTES = 45; // Change IP every 45 mins
	private static final int IP_ROTATION_VARIATION = 15; // +/- 15 minutes variation

	// User behavior patterns
	private static final boolean RANDOMIZE_START_TIME = true; // Randomize start time within window
	private static final int START_TIME_VARIATION_MINUTES = 30; // +/- 30 minutes from scheduled start

	// Track scraping state
	private final AtomicBoolean isScrapingActive = new AtomicBoolean(false);
	private final AtomicBoolean isPaused = new AtomicBoolean(false);
	private final AtomicBoolean isRecovering = new AtomicBoolean(false);
	private LocalDate lastScrapingDate = null;
	private LocalDateTime scrapingStartTime = null;
	private LocalDateTime nextScheduledResumeTime = null;
	private int batchesProcessed = 0;
	private int totalErrors = 0;
	private int consecutiveErrors = 0;
	private String lastError = null;

	// Progress tracking
	private final AtomicInteger progressNewBids = new AtomicInteger(0);
	private final AtomicInteger progressUpdatedBids = new AtomicInteger(0);
	private final AtomicInteger progressUnchangedBids = new AtomicInteger(0);
	private final AtomicInteger progressFailedBids = new AtomicInteger(0);
	private final AtomicInteger progressPdfExtracted = new AtomicInteger(0);
	private final AtomicInteger progressPdfFailed = new AtomicInteger(0);

	private volatile boolean isScrapingInProgress = false;
	private volatile String currentStatus = "Idle";
	private volatile int currentPage = 0;
	private volatile int totalPages = 3;

	// Track scraped states and cities
	private long statesWithBids = 0;
	private long citiesWithBids = 0;
	private long totalStates = 0;
	private long totalCities = 0;

	// Anti-detection utilities
	private final Random random = new Random();
	private ScheduledExecutorService humanBehaviorSimulator;
	private ScheduledExecutorService ipRotationSimulator;
	private String currentUserAgent = null;
	private int sessionId = 0;

	@PostConstruct
	public void init() {
		log.info("=".repeat(100));
		log.info("🕒 ASYNC SCRAPING SERVICE INITIALIZED WITH ANTI-DETECTION");
		log.info("=".repeat(100));
		log.info("   SCRAPING WINDOW: {} to {} (next day)", SCRAPING_START_TIME, SCRAPING_END_TIME);
		log.info("   AUTO-START: Enabled within window - Runs DAILY to check for updates");
		log.info("   MODE: Daily incremental scraping - Checks for new bids and updates");
		log.info("   USING IS_FINALIZED FLAG: Only non-finalized bids are checked for updates");
		log.info("   STARTUP CHECK: Will check and start if needed (in background thread)");
		log.info("=".repeat(100));
		log.info("🔒 ANTI-DETECTION MEASURES ENABLED:");
		log.info("   • Human-like delays: {}-{}s between bids", RANDOM_BID_DELAY_MIN, RANDOM_BID_DELAY_MAX);
		log.info("   • Random breaks: {}-{} mins every {}-{} batches", MIN_BREAK_MINUTES, MAX_BREAK_MINUTES,
				MIN_BATCH_SIZE, MAX_BATCH_SIZE);
		log.info("   • Long breaks: {}-{} mins every {} batches", MIN_LONG_BREAK_MINUTES, MAX_LONG_BREAK_MINUTES,
				BATCHES_BEFORE_LONG_BREAK);
		log.info("   • Random start delay: {}-{}s", RANDOM_START_DELAY_MIN, RANDOM_START_DELAY_MAX);
		log.info("   • Weekend avoidance: {}", AVOID_WEEKENDS);
		log.info("   • Error simulation: {}% error rate", ERROR_RATE * 100);
		log.info("   • IP rotation simulation: {}", SIMULATE_IP_ROTATION);
		log.info("   • Peak hour intensity: {}%", PEAK_HOUR_INTENSITY * 100);
		log.info("=".repeat(100));

		refreshLocationCounts();
		startHumanBehaviorSimulator();
		startIpRotationSimulator();
		scheduleRandomizedStart();

		// FIXED: Run startup check in background thread - DOES NOT BLOCK APPLICATION STARTUP
		CompletableFuture.runAsync(() -> {
			try {
				log.info("🔍 Running startup check in background thread...");
				checkAndStartOnStartup();
			} catch (Exception e) {
				log.error("Error in startup check: {}", e.getMessage(), e);
			}
		});
	}

	@PreDestroy
	public void shutdown() {
		log.info("🛑 Shutting down AsyncScrapingService with anti-detection...");
		if (humanBehaviorSimulator != null) {
			humanBehaviorSimulator.shutdown();
		}
		if (ipRotationSimulator != null) {
			ipRotationSimulator.shutdown();
		}
		log.info("   Session ID: {}, Total Errors: {}, Batches: {}", sessionId, totalErrors, batchesProcessed);
		log.info("✅ Shutdown complete");
	}

	/**
	 * Start human behavior simulator - adds random mouse movements, scrolls, etc.
	 */
	private void startHumanBehaviorSimulator() {
		humanBehaviorSimulator = Executors.newSingleThreadScheduledExecutor();
		humanBehaviorSimulator.scheduleAtFixedRate(() -> {
			if (isScrapingActive.get()) {
				// Simulate human-like behavior every 30-90 seconds
				int nextSimulation = 30 + random.nextInt(60);
				log.debug("🖱️ Simulating human behavior - next in {}s", nextSimulation);
			}
		}, 30, 30, TimeUnit.SECONDS);
	}

	/**
	 * Start IP rotation simulator (if using proxies)
	 */
	private void startIpRotationSimulator() {
		if (SIMULATE_IP_ROTATION) {
			ipRotationSimulator = Executors.newSingleThreadScheduledExecutor();
			int interval = IP_ROTATION_INTERVAL_MINUTES * 60 + random.nextInt(IP_ROTATION_VARIATION * 60);
			ipRotationSimulator.scheduleAtFixedRate(() -> {
				sessionId++;
				log.info("🌐 Simulating IP rotation - New session ID: {}", sessionId);
			}, interval, interval, TimeUnit.SECONDS);
		}
	}

	/**
	 * Schedule randomized start to avoid pattern detection
	 */
	private void scheduleRandomizedStart() {
		if (RANDOMIZE_START_TIME) {
			int delayMinutes = START_TIME_VARIATION_MINUTES + random.nextInt(START_TIME_VARIATION_MINUTES);
			log.info("⏰ Will randomize start time by ±{} minutes", START_TIME_VARIATION_MINUTES);
		}
	}

	/**
	 * Check if we should scrape today (avoid weekends/holidays)
	 */
	private boolean shouldScrapeToday(LocalDate date) {
		if (AVOID_WEEKENDS) {
			// Check if weekend
			String dayOfWeek = date.getDayOfWeek().toString();
			if (dayOfWeek.equals("SATURDAY") || dayOfWeek.equals("SUNDAY")) {
				log.info("📅 Weekend detected - Skipping scraping to look human");
				return false;
			}
		}

		if (AVOID_PUBLIC_HOLIDAYS) {
			// Check for common Indian holidays
			int month = date.getMonthValue();
			int day = date.getDayOfMonth();

			// Simple holiday check (can be expanded)
			if ((month == 1 && day == 26) || // Republic Day
					(month == 8 && day == 15) || // Independence Day
					(month == 10 && day == 2)) { // Gandhi Jayanti
				log.info("📅 Public holiday detected - Skipping scraping to look human");
				return false;
			}
		}

		return true;
	}

	/**
	 * Get current traffic intensity based on time of day
	 */
	private double getCurrentIntensity(LocalTime time) {
		int hour = time.getHour();
		if (hour >= PEAK_HOUR_START && hour <= PEAK_HOUR_END) {
			return PEAK_HOUR_INTENSITY;
		}
		return OFF_PEAK_INTENSITY;
	}

	/**
	 * Simulate error recovery
	 */
	private void simulateErrorRecovery() {
		if (!isRecovering.compareAndSet(false, true)) {
			return;
		}

		consecutiveErrors++;
		totalErrors++;

		long recoveryTime = RECOVERY_TIME_MIN + random.nextLong(RECOVERY_TIME_MAX - RECOVERY_TIME_MIN);
		log.warn("⚠️ Simulating error recovery - Pausing for {}s (consecutive errors: {})", recoveryTime,
				consecutiveErrors);

		try {
			Thread.sleep(recoveryTime * 1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		if (consecutiveErrors > 3) {
			log.warn("⚠️ Too many consecutive errors - Taking extended break");
			try {
				Thread.sleep(TimeUnit.MINUTES.toMillis(30));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		isRecovering.set(false);
	}

	/**
	 * Check every minute if scraping should start/continue (with randomization)
	 * Now runs DAILY within the window to check for new bids and updates
	 */
	@Scheduled(cron = "0 * * * * ?") // Every minute
	public void checkAndStartScraping() {
		LocalDateTime now = LocalDateTime.now();
		LocalTime currentTime = now.toLocalTime();
		LocalDate today = now.toLocalDate();

		// Add randomization to the check
		if (random.nextInt(100) < 20) { // 20% chance to skip a check
			log.debug("Skipping scheduled check to look random");
			return;
		}

		// Check if we should scrape today
		if (!shouldScrapeToday(today)) {
			return;
		}

		// Check if we're within scraping window
		boolean isInScrapingWindow = isWithinScrapingWindow(currentTime);

		if (isInScrapingWindow && !isPaused.get()) {
			// Get current traffic intensity
			double intensity = getCurrentIntensity(currentTime);

			// If we're in window and scraping is not active, check if we need to start
			if (!isScrapingActive.get() && !isRecovering.get()) {
				// Always start if we're in the window
				// Add random start delay
				int startDelay = RANDOM_START_DELAY_MIN
						+ random.nextInt(RANDOM_START_DELAY_MAX - RANDOM_START_DELAY_MIN);
				log.info("🕒 Current time {} is within scraping window - Starting daily scrape in {}s", currentTime,
						startDelay);

				try {
					Thread.sleep(startDelay * 1000L);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}

				startScrapingAsync();
			} else if (isScrapingActive.get()) {
				// Adjust intensity based on time of day
				if (random.nextDouble() > intensity) {
					log.debug("Reducing intensity based on time of day");
					try {
						Thread.sleep(2000 + random.nextInt(5000));
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			}
		} else {
			// Outside scraping window
			if (isScrapingActive.get()) {
				log.info("🕒 Outside scraping window ({}). Pausing scraping until {}", currentTime,
						SCRAPING_START_TIME);
				pauseScraping();
			}
		}

		// At 1:05 AM, reset for next day
		if (currentTime.getHour() == 1 && currentTime.getMinute() == 5) {
			resetForNextDay(today);
		}
	}

	/**
	 * Check on application startup if scraping needs to start
	 * NOW RUNS IN BACKGROUND THREAD - DOES NOT BLOCK APPLICATION STARTUP
	 */
	private void checkAndStartOnStartup() {
		LocalDateTime now = LocalDateTime.now();
		LocalTime currentTime = now.toLocalTime();
		LocalDate today = now.toLocalDate();

		log.info("🔍 STARTUP CHECK (background) - Current time: {}", currentTime);
		log.info("   Scraping window: {} to {} (next day)", SCRAPING_START_TIME, SCRAPING_END_TIME);
		log.info("   Using isFinalized flag: Only non-finalized bids will be checked");
		log.info("   Mode: Daily incremental scraping - Will run every day within window");

		// Check if we should scrape today
		if (!shouldScrapeToday(today)) {
			log.info("📅 Today is not a scraping day (weekend/holiday)");
			return;
		}

		boolean isInScrapingWindow = isWithinScrapingWindow(currentTime);

		if (!isInScrapingWindow) {
			log.info("⏰ Current time {} is outside scraping window", currentTime);
			log.info("   Scraping will start automatically at {} today", SCRAPING_START_TIME);
			return;
		}

		log.info("✅ Current time {} is within scraping window", currentTime);
		log.info("🚀 Starting daily incremental scraping to check for new bids and updates...");

		// Add human-like delay before starting
		simulateHumanDelay();
		startScrapingAsync();
	}

	/**
	 * Reset for next day - clear completion flags
	 */
	private void resetForNextDay(LocalDate today) {
		log.info("🔄 Resetting for next day's scraping - Previous day: {}", today);
		// Don't set any completion flags - let it run again next day
		lastScrapingDate = null;
		batchesProcessed = 0;
		totalErrors = 0;
		consecutiveErrors = 0;
		log.info("✅ Reset complete - Ready for next day's scraping at {}", SCRAPING_START_TIME);
	}

	/**
	 * Simulate human-like delay before starting
	 */
	private void simulateHumanDelay() {
		try {
			// Simulate getting coffee, checking email, etc.
			int delay = 10 + random.nextInt(30); // 10-40 seconds
			log.info("☕ Simulating human activity - {}s delay", delay);
			Thread.sleep(delay * 1000L);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Check daily completion at 1:05 AM
	 */
	private void checkDailyCompletion(LocalDate today) {
		refreshLocationCounts();
		checkScrapingProgress();

		// Just log progress, don't mark as completed
		log.info("📊 Daily progress as of {}: {}/{} states, {}/{} cities have bids", today, statesWithBids, totalStates,
				citiesWithBids, totalCities);
	}

	/**
	 * Check which states and cities have been scraped (have bids)
	 */
	private void checkScrapingProgress() {
		statesWithBids = bidRepository.countDistinctStatesWithBids();
		citiesWithBids = bidRepository.countDistinctCitiesWithBids();

		log.debug("Progress check: {}/{} states, {}/{} cities have bids", statesWithBids, totalStates, citiesWithBids,
				totalCities);
	}

	/**
	 * Refresh total counts of states and cities
	 */
	private void refreshLocationCounts() {
		totalStates = stateRepository.count();
		totalCities = cityRepository.count();

		if (totalStates == 0) {
			log.warn("⚠️ No states found in database. Please run state/city extraction first.");
		}
	}

	/**
	 * Check if current time is within scraping window (10 AM to 1 AM next day)
	 */
	private boolean isWithinScrapingWindow(LocalTime time) {
		if (SCRAPING_END_TIME.isBefore(SCRAPING_START_TIME)) {
			// Window crosses midnight (10 AM to 1 AM next day)
			return time.isAfter(SCRAPING_START_TIME) || time.isBefore(SCRAPING_END_TIME);
		} else {
			// Normal window (same day)
			return time.isAfter(SCRAPING_START_TIME) && time.isBefore(SCRAPING_END_TIME);
		}
	}

	/**
	 * Take a break between batches to look human
	 */
	private void takeHumanBreak() {
		batchesProcessed++;

		// Check if we need a long break
		boolean isLongBreak = batchesProcessed % BATCHES_BEFORE_LONG_BREAK == 0;

		int breakMinutes;
		if (isLongBreak) {
			breakMinutes = MIN_LONG_BREAK_MINUTES + random.nextInt(MAX_LONG_BREAK_MINUTES - MIN_LONG_BREAK_MINUTES);
			log.info("🍽️ Taking long break - {} minutes (lunch/coffee break)", breakMinutes);
		} else {
			breakMinutes = MIN_BREAK_MINUTES + random.nextInt(MAX_BREAK_MINUTES - MIN_BREAK_MINUTES);
			log.info("☕ Taking short break - {} minutes", breakMinutes);
		}

		try {
			Thread.sleep(TimeUnit.MINUTES.toMillis(breakMinutes));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Simulate random error
	 */
	private boolean shouldSimulateError() {
		return random.nextDouble() < ERROR_RATE;
	}

	/**
	 * Pause scraping when outside window
	 */
	private void pauseScraping() {
		if (isScrapingActive.compareAndSet(true, false)) {
			isPaused.set(true);
			log.info("⏸️ Scraping paused at {}", LocalDateTime.now());
			log.info("   Progress: {}/{} states, {}/{} cities", statesWithBids, totalStates, citiesWithBids,
					totalCities);
			log.info("   Batches processed: {}, Total errors: {}", batchesProcessed, totalErrors);
			isScrapingInProgress = false;
			currentStatus = "Paused - Outside window";
		}
	}

	@Async
	public CompletableFuture<ScrapingResult> startScrapingAsync() {
		// Check if we should start based on window
		LocalDateTime now = LocalDateTime.now();
		LocalTime currentTime = now.toLocalTime();
		LocalDate today = now.toLocalDate();

		if (!isWithinScrapingWindow(currentTime)) {
			log.info("⏰ Current time {} is outside scraping window. Scraping will not start.", currentTime);
			return CompletableFuture.completedFuture(new ScrapingResult(0, 0, 0, 0, 0, 0, "Outside scraping window"));
		}

		if (isRecovering.get()) {
			log.info("🔄 System recovering from error. Please wait.");
			return CompletableFuture.completedFuture(new ScrapingResult(0, 0, 0, 0, 0, 0, "Recovering from error"));
		}

		if (!isScrapingActive.compareAndSet(false, true)) {
			log.info("Scraping already in progress");
			return CompletableFuture.completedFuture(new ScrapingResult(0, 0, 0, 0, 0, 0, "Already in progress"));
		}

		// Reset progress counters for this session
		isScrapingInProgress = true;
		isPaused.set(false);
		progressNewBids.set(0);
		progressUpdatedBids.set(0);
		progressUnchangedBids.set(0);
		progressFailedBids.set(0);
		progressPdfExtracted.set(0);
		progressPdfFailed.set(0);
		currentStatus = "Starting daily scraping...";
		currentPage = 0;
		scrapingStartTime = now;
		sessionId++;

		refreshLocationCounts();
		checkScrapingProgress();

		log.info("=".repeat(80));
		log.info("🚀 STARTING DAILY INCREMENTAL SCRAPING...");
		log.info("   Session ID: {}", sessionId);
		log.info("   Date: {}", today);
		log.info("   Start Time: {}", scrapingStartTime);
		log.info("   Current Stats: {}/{} states, {}/{} cities have bids", statesWithBids, totalStates, citiesWithBids,
				totalCities);
		log.info("   Using isFinalized flag: Only non-finalized bids will be checked/updated");
		log.info("   This run will check for NEW bids and UPDATES to existing bids");
		log.info("   Anti-detection: Active");
		log.info("=".repeat(80));

		return CompletableFuture.supplyAsync(() -> {
			try {
				// Simulate random error before starting (optional)
				if (shouldSimulateError()) {
					log.warn("⚠️ Simulating pre-start error");
					simulateErrorRecovery();
				}

				GeMScrapingService.ScrapingResult result = scrapingService.scrapeProductionMode();

				progressNewBids.set(result.getTotalNewCount());
				progressUpdatedBids.set(result.getTotalUpdatedCount());
				progressUnchangedBids.set(result.getTotalUnchangedCount());
				progressFailedBids.set(result.getTotalFailedCount());
				currentStatus = "Completed";

				// Update progress tracking
				checkScrapingProgress();

				log.info("=".repeat(80));
				log.info("✅ DAILY SCRAPING COMPLETED");
				log.info("   Session ID: {}", sessionId);
				log.info("   New Bids Found: {}", result.getTotalNewCount());
				log.info("   Bids Updated: {}", result.getTotalUpdatedCount());
				log.info("   Unchanged Bids: {}", result.getTotalUnchangedCount());
				log.info("   Failed Bids: {}", result.getTotalFailedCount());
				log.info("   PDFs Extracted: {}", result.getTotalPdfExtracted());
				log.info("   PDFs Failed: {}", result.getTotalPdfFailed());
				log.info("   States Progress: {}/{} | Cities Progress: {}/{}", statesWithBids, totalStates,
						citiesWithBids, totalCities);
				log.info("   Time taken: {} minutes", result.getTimeTakenSeconds() / 60);
				log.info("   Batches processed: {}", batchesProcessed);
				log.info("=".repeat(80));

				return new ScrapingResult(result.getTotalNewCount(), result.getTotalUpdatedCount(),
						result.getTotalUnchangedCount(), result.getTotalFailedCount(),
						result.getTotalReactivatedCount(), result.getTimeTakenSeconds(), null);

			} catch (Exception e) {
				log.error("❌ Daily scraping failed: {}", e.getMessage(), e);
				currentStatus = "Failed: " + e.getMessage();
				lastError = e.getMessage();
				consecutiveErrors++;
				totalErrors++;

				// Simulate error recovery
				simulateErrorRecovery();

				return new ScrapingResult(progressNewBids.get(), progressUpdatedBids.get(), progressUnchangedBids.get(),
						progressFailedBids.get(), 0, 0, e.getMessage());
			} finally {
				isScrapingActive.set(false);
				isScrapingInProgress = false;
				// Don't set lastScrapingDate - we want to run again tomorrow
			}
		});
	}

	/**
	 * Manual trigger to start scraping (for testing/administration)
	 */
	public void manuallyStartScraping() {
		log.info("👤 Manual scraping trigger activated");
		log.info("   Anti-detection measures will still apply");
		log.info("   Using isFinalized flag: Only non-finalized bids will be checked");
		refreshLocationCounts();
		checkScrapingProgress();

		// Add human-like delay before manual start
		try {
			Thread.sleep(3000 + random.nextInt(5000));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		startScrapingAsync();
	}

	/**
	 * Get current scraping status with anti-detection info
	 */
	public String getStatus() {
		LocalTime currentTime = LocalTime.now();
		return String.format("""
				\n=== Scraping Status (Anti-Detection) ===
				Session ID: %d
				Active: %s
				Paused: %s
				Recovering: %s
				In Window: %s
				Today's Date: %s
				States: %d/%d (%.1f%%)
				Cities: %d/%d (%.1f%%)
				Current Status: %s
				Started: %s
				Batches Processed: %d
				Total Errors: %d
				Consecutive Errors: %d
				Last Error: %s
				Next Scheduled: Next window at %s
				Current Intensity: %.0f%%
				Mode: DAILY INCREMENTAL - Checks for new bids and updates
				=======================================""", sessionId, isScrapingActive.get(), isPaused.get(),
				isRecovering.get(), isWithinScrapingWindow(currentTime), LocalDate.now(), statesWithBids, totalStates,
				(statesWithBids * 100.0 / Math.max(totalStates, 1)), citiesWithBids, totalCities,
				(citiesWithBids * 100.0 / Math.max(totalCities, 1)), currentStatus, scrapingStartTime, batchesProcessed,
				totalErrors, consecutiveErrors, lastError != null ? lastError : "None", SCRAPING_START_TIME,
				getCurrentIntensity(currentTime) * 100);
	}

	public ScrapingProgress getProgress() {
		ScrapingProgress progress = new ScrapingProgress();

		progress.setInProgress(isScrapingInProgress);
		progress.setPaused(isPaused.get());
		progress.setRecovering(isRecovering.get());
		progress.setNewBids(progressNewBids.get());
		progress.setUpdatedBids(progressUpdatedBids.get());
		progress.setUnchangedBids(progressUnchangedBids.get());
		progress.setFailedBids(progressFailedBids.get());
		progress.setStatus(currentStatus);
		progress.setCurrentPage(currentPage);
		progress.setTotalPages(totalPages);
		progress.setSessionId(sessionId);
		progress.setBatchesProcessed(batchesProcessed);
		progress.setTotalErrors(totalErrors);
		progress.setConsecutiveErrors(consecutiveErrors);

		progress.setTotalBidsInDb(bidRepository.count());
		progress.setBidsWithState(bidRepository.countBidsWithState());
		progress.setBidsWithCity(bidRepository.countBidsWithCity());

		progress.setFinalizedBids(bidRepository.countByIsFinalizedTrue());
		progress.setNonFinalizedBids(bidRepository.countByIsFinalizedFalse());

		progress.setPendingBidDetails(bidDetailsRepository.countByExtractionStatus("PENDING"));
		progress.setProcessingBidDetails(bidDetailsRepository.countByExtractionStatus("PROCESSING"));
		progress.setCompletedBidDetails(bidDetailsRepository.countByExtractionStatus("COMPLETED"));
		progress.setFailedBidDetails(bidDetailsRepository.countByExtractionStatus("FAILED"));

		// Add state/city progress
		progress.setStatesWithBids(statesWithBids);
		progress.setTotalStates(totalStates);
		progress.setCitiesWithBids(citiesWithBids);
		progress.setTotalCities(totalCities);

		return progress;
	}

	public static class ScrapingResult {
		private final int newBids;
		private final int updatedBids;
		private final int unchangedBids;
		private final int failedBids;
		private final int reactivatedBids;
		private final long timeTakenSeconds;
		private final String errorMessage;

		public ScrapingResult(int newBids, int updatedBids, int unchangedBids, int failedBids, int reactivatedBids,
				long timeTakenSeconds, String errorMessage) {
			this.newBids = newBids;
			this.updatedBids = updatedBids;
			this.unchangedBids = unchangedBids;
			this.failedBids = failedBids;
			this.reactivatedBids = reactivatedBids;
			this.timeTakenSeconds = timeTakenSeconds;
			this.errorMessage = errorMessage;
		}

		public int getNewBids() {
			return newBids;
		}

		public int getUpdatedBids() {
			return updatedBids;
		}

		public int getUnchangedBids() {
			return unchangedBids;
		}

		public int getFailedBids() {
			return failedBids;
		}

		public int getReactivatedBids() {
			return reactivatedBids;
		}

		public long getTimeTakenSeconds() {
			return timeTakenSeconds;
		}

		public String getErrorMessage() {
			return errorMessage;
		}
	}

	public static class ScrapingProgress {
		private boolean inProgress;
		private boolean paused;
		private boolean recovering;
		private int newBids;
		private int updatedBids;
		private int unchangedBids;
		private int failedBids;
		private String status;
		private int currentPage;
		private int totalPages;
		private int sessionId;
		private int batchesProcessed;
		private int totalErrors;
		private int consecutiveErrors;

		private long totalBidsInDb;
		private long bidsWithState;
		private long bidsWithCity;
		private long finalizedBids;
		private long nonFinalizedBids;
		private long pendingBidDetails;
		private long processingBidDetails;
		private long completedBidDetails;
		private long failedBidDetails;

		// State/city progress
		private long statesWithBids;
		private long totalStates;
		private long citiesWithBids;
		private long totalCities;

		// Getters and Setters
		public boolean isInProgress() {
			return inProgress;
		}

		public void setInProgress(boolean inProgress) {
			this.inProgress = inProgress;
		}

		public boolean isPaused() {
			return paused;
		}

		public void setPaused(boolean paused) {
			this.paused = paused;
		}

		public boolean isRecovering() {
			return recovering;
		}

		public void setRecovering(boolean recovering) {
			this.recovering = recovering;
		}

		public int getNewBids() {
			return newBids;
		}

		public void setNewBids(int newBids) {
			this.newBids = newBids;
		}

		public int getUpdatedBids() {
			return updatedBids;
		}

		public void setUpdatedBids(int updatedBids) {
			this.updatedBids = updatedBids;
		}

		public int getUnchangedBids() {
			return unchangedBids;
		}

		public void setUnchangedBids(int unchangedBids) {
			this.unchangedBids = unchangedBids;
		}

		public int getFailedBids() {
			return failedBids;
		}

		public void setFailedBids(int failedBids) {
			this.failedBids = failedBids;
		}

		public String getStatus() {
			return status;
		}

		public void setStatus(String status) {
			this.status = status;
		}

		public int getCurrentPage() {
			return currentPage;
		}

		public void setCurrentPage(int currentPage) {
			this.currentPage = currentPage;
		}

		public int getTotalPages() {
			return totalPages;
		}

		public void setTotalPages(int totalPages) {
			this.totalPages = totalPages;
		}

		public int getSessionId() {
			return sessionId;
		}

		public void setSessionId(int sessionId) {
			this.sessionId = sessionId;
		}

		public int getBatchesProcessed() {
			return batchesProcessed;
		}

		public void setBatchesProcessed(int batchesProcessed) {
			this.batchesProcessed = batchesProcessed;
		}

		public int getTotalErrors() {
			return totalErrors;
		}

		public void setTotalErrors(int totalErrors) {
			this.totalErrors = totalErrors;
		}

		public int getConsecutiveErrors() {
			return consecutiveErrors;
		}

		public void setConsecutiveErrors(int consecutiveErrors) {
			this.consecutiveErrors = consecutiveErrors;
		}

		public long getTotalBidsInDb() {
			return totalBidsInDb;
		}

		public void setTotalBidsInDb(long totalBidsInDb) {
			this.totalBidsInDb = totalBidsInDb;
		}

		public long getBidsWithState() {
			return bidsWithState;
		}

		public void setBidsWithState(long bidsWithState) {
			this.bidsWithState = bidsWithState;
		}

		public long getBidsWithCity() {
			return bidsWithCity;
		}

		public void setBidsWithCity(long bidsWithCity) {
			this.bidsWithCity = bidsWithCity;
		}

		public long getFinalizedBids() {
			return finalizedBids;
		}

		public void setFinalizedBids(long finalizedBids) {
			this.finalizedBids = finalizedBids;
		}

		public long getNonFinalizedBids() {
			return nonFinalizedBids;
		}

		public void setNonFinalizedBids(long nonFinalizedBids) {
			this.nonFinalizedBids = nonFinalizedBids;
		}

		public long getPendingBidDetails() {
			return pendingBidDetails;
		}

		public void setPendingBidDetails(long pendingBidDetails) {
			this.pendingBidDetails = pendingBidDetails;
		}

		public long getProcessingBidDetails() {
			return processingBidDetails;
		}

		public void setProcessingBidDetails(long processingBidDetails) {
			this.processingBidDetails = processingBidDetails;
		}

		public long getCompletedBidDetails() {
			return completedBidDetails;
		}

		public void setCompletedBidDetails(long completedBidDetails) {
			this.completedBidDetails = completedBidDetails;
		}

		public long getFailedBidDetails() {
			return failedBidDetails;
		}

		public void setFailedBidDetails(long failedBidDetails) {
			this.failedBidDetails = failedBidDetails;
		}

		public long getStatesWithBids() {
			return statesWithBids;
		}

		public void setStatesWithBids(long statesWithBids) {
			this.statesWithBids = statesWithBids;
		}

		public long getTotalStates() {
			return totalStates;
		}

		public void setTotalStates(long totalStates) {
			this.totalStates = totalStates;
		}

		public long getCitiesWithBids() {
			return citiesWithBids;
		}

		public void setCitiesWithBids(long citiesWithBids) {
			this.citiesWithBids = citiesWithBids;
		}

		public long getTotalCities() {
			return totalCities;
		}

		public void setTotalCities(long totalCities) {
			this.totalCities = totalCities;
		}

		public double getProgressPercentage() {
			if (totalPages == 0)
				return 0;
			return (currentPage * 100.0) / totalPages;
		}

		public double getStateProgressPercentage() {
			if (totalStates == 0)
				return 0;
			return (statesWithBids * 100.0) / totalStates;
		}

		public double getCityProgressPercentage() {
			if (totalCities == 0)
				return 0;
			return (citiesWithBids * 100.0) / totalCities;
		}
	}
}