package in.BidPilots.service;

import in.BidPilots.entity.Bid;
import in.BidPilots.entity.State;
import in.BidPilots.entity.City;
import in.BidPilots.entity.BidDetails;
import in.BidPilots.repository.BidRepository;
import in.BidPilots.repository.BidDetailsRepository;
import in.BidPilots.repository.StateRepository;
import in.BidPilots.repository.CityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.Select;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.bonigarcia.wdm.WebDriverManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class GeMScrapingService {

    private final BidRepository bidRepository;
    private final StateRepository stateRepository;
    private final CityRepository cityRepository;
    private final BidDetailsRepository bidDetailsRepository;
    private final BidDetailsService bidDetailsService;

    @Value("${gem.portal.base.url:https://bidplus.gem.gov.in}")
    private String baseUrl;

    private static final DateTimeFormatter GEM_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy h:mm a",
            Locale.ENGLISH);
    private static final DateTimeFormatter GEM_DATE_FORMATTER_2 = DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm a",
            Locale.ENGLISH);

    // ANTI-DETECTION CONFIGURATION
    private static final int THREAD_POOL_SIZE = 2;
    private static final int PAGE_LOAD_TIMEOUT = 60;
    private static final int MAX_RETRIES = 3;
    private static final int BASE_DELAY_MS = 5000;
    private static final int RANDOM_DELAY_RANGE = 5000;

    // Rotating user agents
    private static final String[] USER_AGENTS = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36 Edg/119.0.0.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Safari/605.1.15",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    };

    // Viewport sizes
    private static final String[] VIEWPORT_SIZES = {
        "1920,1080", "1366,768", "1536,864", "1440,900", "1280,720"
    };

    // Scraping constants
    private static final int TEST_PAGES_TO_SCRAPE = 3;
    private static final int PRODUCTION_PAGES_TO_SCRAPE = Integer.MAX_VALUE;

    // FIX: Single job guard - prevents two full scrapes from running at once
    private final AtomicBoolean isJobRunning = new AtomicBoolean(false);

    private ScheduledExecutorService progressLogger;
    private final Random random = new Random();

    // -------------------------------------------------------------------------
    // Public entry points
    // -------------------------------------------------------------------------

    public ScrapingResult scrapeTestMode() {
        log.info("=".repeat(80));
        log.info("🧪 TEST MODE SCRAPING - ANTI-DETECTION ENABLED");
        log.info("   Threads: {}, Random Delays: {}ms", THREAD_POOL_SIZE, RANDOM_DELAY_RANGE);
        log.info("=".repeat(80));
        return scrapeInternal(TEST_PAGES_TO_SCRAPE, "TEST");
    }

    public ScrapingResult scrapeProductionMode() {
        log.info("=".repeat(80));
        log.info("🏭 PRODUCTION MODE SCRAPING - STEALTH MODE ACTIVE");
        log.info("   Threads: {}, Rotating User Agents, Random Delays", THREAD_POOL_SIZE);
        log.info("   Mimicking Human Behavior, Anti-Detection Enabled");
        log.info("   Mode: Scraping ALL pages until last page");
        log.info("=".repeat(80));
        return scrapeInternal(PRODUCTION_PAGES_TO_SCRAPE, "PRODUCTION");
    }

    // -------------------------------------------------------------------------
    // Internal scrape orchestrator
    // FIX: @Transactional removed - this method manages threads and does no DB work itself.
    //      Per-bid DB work is done in processSingleBidWithPdf (REQUIRES_NEW).
    // FIX: JobContext replaces singleton AtomicInteger fields so concurrent jobs
    //      don't share counters.
    // -------------------------------------------------------------------------
    private ScrapingResult scrapeInternal(int maxPages, String mode) {

        // FIX: Prevent concurrent full-scrape runs
        if (!isJobRunning.compareAndSet(false, true)) {
            log.warn("⚠️ A scraping job is already running. Skipping this request.");
            ScrapingResult skipped = new ScrapingResult();
            skipped.setErrorMessage("Job already running");
            return skipped;
        }

        ScrapingResult result = new ScrapingResult();
        long startTime = System.currentTimeMillis();

        // FIX: Job-scoped counters - not shared between concurrent calls
        JobContext ctx = new JobContext();

        startProgressLogger(mode, ctx);

        try {
            ensureStatesAndCitiesPopulated();
            List<State> allStates = stateRepository.findAll();
            log.info("Found {} states to process", allStates.size());

            // Shuffle states to avoid pattern detection
            Collections.shuffle(allStates);

            ExecutorService stateExecutor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (State state : allStates) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() ->
                    processState(state, maxPages, ctx), stateExecutor
                ).exceptionally(throwable -> {
                    log.error("Failed to process state {}: {}", state.getStateName(), throwable.getMessage());
                    ctx.totalFailedCount.incrementAndGet();
                    return null;
                });
                futures.add(future);

                // Random delay between starting threads
                sleepRandom(3000, 7000);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(12, TimeUnit.HOURS)
                .join();

            stateExecutor.shutdown();
            stateExecutor.awaitTermination(1, TimeUnit.HOURS);

            long timeTaken = (System.currentTimeMillis() - startTime) / 1000;
            result.setTimeTakenSeconds(timeTaken);
            result.setTotalNewCount(ctx.totalNewCount.get());
            result.setTotalUpdatedCount(ctx.totalUpdatedCount.get());
            result.setTotalUnchangedCount(ctx.totalUnchangedCount.get());
            result.setTotalFailedCount(ctx.totalFailedCount.get());
            result.setTotalReactivatedCount(ctx.totalReactivatedCount.get());
            result.setTotalPdfExtracted(ctx.totalPdfExtractedCount.get());
            result.setTotalPdfFailed(ctx.totalPdfFailedCount.get());

            log.info("=".repeat(80));
            log.info("✅ {} SCRAPING COMPLETED - STEALTH MODE", mode);
            log.info("   New: {}, Updated: {}, Unchanged: {}, Reactivated: {}, Failed: {}",
                    ctx.totalNewCount.get(), ctx.totalUpdatedCount.get(), ctx.totalUnchangedCount.get(),
                    ctx.totalReactivatedCount.get(), ctx.totalFailedCount.get());
            log.info("   PDF Extraction - Success: {}, Failed: {}", ctx.totalPdfExtractedCount.get(), ctx.totalPdfFailedCount.get());
            log.info("   Time: {} minutes", timeTaken / 60);
            log.info("=".repeat(80));

            return result;

        } catch (Exception e) {
            log.error("Fatal error in {} scraping: {}", mode, e.getMessage(), e);
            result.setErrorMessage(e.getMessage());
            return result;
        } finally {
            stopProgressLogger();
            isJobRunning.set(false);
        }
    }

    // -------------------------------------------------------------------------
    // Location-based scrape (state + city)
    // FIX: @Transactional removed from this method - it spins a WebDriver loop,
    //      not a DB transaction. Individual bid saves use REQUIRES_NEW below.
    // FIX: ScrapingResult fields written from single thread only (no data race).
    // FIX: WebDriver always closed in finally block.
    // -------------------------------------------------------------------------
    public ScrapingResult scrapeBidsByLocation(State state, City city, int maxPages) {
        return scrapeBidsByLocation(state, city, maxPages, new JobContext());
    }

    public ScrapingResult scrapeBidsByLocation(State state, City city, int maxPages, JobContext ctx) {
        ScrapingResult result = new ScrapingResult();
        long startTime = System.currentTimeMillis();

        WebDriver driver = null;

        try {
            log.info("=".repeat(80));
            log.info("🕷️ STARTING LOCATION-BASED SCRAPING - {}-{}", state.getStateName(), city.getCityName());
            log.info("   Max Pages: {} (will stop at last page)", maxPages == Integer.MAX_VALUE ? "ALL PAGES" : String.valueOf(maxPages));
            log.info("   PDF Extraction: SYNCHRONOUS (immediate)");
            log.info("   Using isFinalized flag: Only non-finalized bids are considered for updates");
            log.info("=".repeat(80));

            driver = createStealthWebDriver();

            log.info("Navigating to GeM portal...");
            navigateWithRetry(driver, baseUrl);
            sleepRandom(3000, 7000);

            navigateWithRetry(driver, baseUrl + "/advance-search");
            sleepRandom(5000, 10000);

            clickElementNaturally(driver, By.cssSelector("a[href='#tab2']"));
            sleepRandom(2000, 4000);

            selectDropdownNaturally(driver, By.id("state_name_con"), state.getStateName());
            sleepRandom(2000, 4000);

            try {
                selectDropdownNaturally(driver, By.id("city_name_con"), city.getCityName());
                sleepRandom(1000, 3000);
            } catch (Exception e) {
                log.warn("City dropdown not available for {}", city.getCityName());
            }

            clickElementNaturally(driver, By.xpath("//a[contains(@onclick, 'searchBid') and contains(@onclick, 'con')]"));
            sleepRandom(5000, 10000);

            if (noResultsFound(driver)) {
                log.info("No bids found for {}-{}", state.getStateName(), city.getCityName());
                result.setTimeTakenSeconds((System.currentTimeMillis() - startTime) / 1000);
                return result;
            }

            // ---- Detect total pages from pagination bar on page 1 ----
            int totalPages = getTotalPages(driver);
            int effectiveMax = (maxPages == Integer.MAX_VALUE) ? totalPages : Math.min(maxPages, totalPages);

            log.info("📊 Total pages for {}-{}: {} (will scrape all {})",
                    state.getStateName(), city.getCityName(), totalPages, effectiveMax);

            // ---- Scrape page 1 ----
            int pageNumber = 1;
            scrapeOnePage(driver, state, city, pageNumber, effectiveMax, ctx);
            result.setCurrentPage(pageNumber);

            // ---- Scrape pages 2..effectiveMax ----
            while (pageNumber < effectiveMax) {
                int nextPage = pageNumber + 1;

                log.info("➡️ Navigating to page {}/{}", nextPage, effectiveMax);
                boolean navigated = navigateToPage(driver, nextPage, pageNumber);

                if (!navigated) {
                    log.warn("⚠️ Navigation to page {} failed, waiting 15s and retrying...", nextPage);
                    sleepRandom(15000, 20000);
                    navigated = navigateToPage(driver, nextPage, pageNumber);
                }

                if (!navigated) {
                    log.error("❌ Could not navigate to page {}, stopping pagination for {}-{}",
                            nextPage, state.getStateName(), city.getCityName());
                    break;
                }

                int confirmedPage = getCurrentPageNumber(driver);
                if (confirmedPage > 0 && confirmedPage != nextPage) {
                    log.warn("⚠️ Expected page {} but pagination shows {} — continuing anyway", nextPage, confirmedPage);
                }

                pageNumber = nextPage;
                result.setCurrentPage(pageNumber);
                scrapeOnePage(driver, state, city, pageNumber, effectiveMax, ctx);

                sleepRandom(8000, 15000);
            }

            long timeTaken = (System.currentTimeMillis() - startTime) / 1000;
            result.setTimeTakenSeconds(timeTaken);
            result.setTotalNewCount(ctx.totalNewCount.get());
            result.setTotalUpdatedCount(ctx.totalUpdatedCount.get());
            result.setTotalUnchangedCount(ctx.totalUnchangedCount.get());
            result.setTotalFailedCount(ctx.totalFailedCount.get());
            result.setTotalReactivatedCount(ctx.totalReactivatedCount.get());
            result.setTotalPdfExtracted(ctx.totalPdfExtractedCount.get());
            result.setTotalPdfFailed(ctx.totalPdfFailedCount.get());
            result.setPagesScraped(pageNumber);

            log.info("=".repeat(80));
            log.info("✅ LOCATION-BASED SCRAPING COMPLETED - {}-{}", state.getStateName(), city.getCityName());
            log.info("   Pages Scraped: {}, New: {}, Updated: {}, Unchanged: {}, Failed: {}",
                    pageNumber, ctx.totalNewCount.get(), ctx.totalUpdatedCount.get(),
                    ctx.totalUnchangedCount.get(), ctx.totalFailedCount.get());
            log.info("   PDF Extraction - Success: {}, Failed: {}", ctx.totalPdfExtractedCount.get(), ctx.totalPdfFailedCount.get());
            log.info("   Time: {} seconds", timeTaken);
            log.info("=".repeat(80));

            return result;

        } catch (Exception e) {
            log.error("Fatal error in location-based scraping: {}", e.getMessage(), e);
            result.setErrorMessage(e.getMessage());
            return result;
        } finally {
            // FIX: Always close WebDriver even on exception or interrupt
            if (driver != null) {
                try {
                    sleepRandom(3000, 6000);
                    driver.quit();
                } catch (Exception e) {
                    log.error("Error closing driver: {}", e.getMessage());
                }
            }
        }
    }

    private void processState(State state, int maxPages, JobContext ctx) {
        String threadName = Thread.currentThread().getName();
        log.info("[{}] Starting state: {}", threadName, state.getStateName());

        try {
            List<City> cities = cityRepository.findByState(state);
            if (cities.isEmpty()) {
                log.warn("[{}] No cities found for state: {}", threadName, state.getStateName());
                return;
            }

            Collections.shuffle(cities);

            for (City city : cities) {
                try {
                    scrapeBidsByLocation(state, city, maxPages, ctx);

                    if (maxPages == PRODUCTION_PAGES_TO_SCRAPE) {
                        sleepRandom(30000, 60000);
                    } else {
                        sleepRandom(10000, 20000);
                    }
                } catch (Exception e) {
                    log.error("[{}] Failed to process city {}-{}: {}",
                            threadName, state.getStateName(), city.getCityName(), e.getMessage());
                    ctx.totalFailedCount.incrementAndGet();
                }
            }

            log.info("[{}] Completed state: {}", threadName, state.getStateName());

        } catch (Exception e) {
            log.error("[{}] Error processing state {}: {}", threadName, state.getStateName(), e.getMessage());
            ctx.totalFailedCount.incrementAndGet();
        }
    }

    /**
     * Process a single bid - save AND extract PDF SYNCHRONOUSLY.
     * REQUIRES_NEW gives each bid its own transaction so failures don't roll
     * back the whole page.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
                   noRollbackFor = {DataIntegrityViolationException.class,
                                    ObjectOptimisticLockingFailureException.class})
    public void processSingleBidWithPdf(Bid scrapedBid, JobContext ctx) {
        Bid savedBid = null;

        try {
            Optional<Bid> existingBidOpt = bidRepository.findByBidNumberAndNotFinalized(scrapedBid.getBidNumber());

            if (existingBidOpt.isPresent()) {
                Bid existingBid = existingBidOpt.get();
                boolean wasDeactivated = existingBid.getIsDeactive() != null && existingBid.getIsDeactive();
                boolean hasChanges = hasBidChanged(existingBid, scrapedBid);

                if (hasChanges) {
                    updateExistingBid(existingBid, scrapedBid);

                    if (scrapedBid.getBidEndDate() != null) {
                        boolean isActive = scrapedBid.getBidEndDate().isAfter(LocalDateTime.now());
                        existingBid.setIsActive(isActive);
                        existingBid.setIsDeactive(!isActive);
                    } else {
                        existingBid.setIsActive(true);
                        existingBid.setIsDeactive(false);
                    }

                    if (wasDeactivated && existingBid.getIsActive()) {
                        log.info("🔄 REACTIVATING bid {} - was previously deactivated", existingBid.getBidNumber());
                        ctx.totalReactivatedCount.incrementAndGet();
                    }

                    savedBid = bidRepository.save(existingBid);
                    ctx.totalUpdatedCount.incrementAndGet();
                    log.info("📝 UPDATED bid: {} - Data changed", existingBid.getBidNumber());

                } else {
                    boolean dateChanged = false;

                    if (!Objects.equals(existingBid.getBidEndDate(), scrapedBid.getBidEndDate())) {
                        existingBid.setBidEndDate(scrapedBid.getBidEndDate());
                        dateChanged = true;
                    }
                    if (!Objects.equals(existingBid.getBidStartDate(), scrapedBid.getBidStartDate())) {
                        existingBid.setBidStartDate(scrapedBid.getBidStartDate());
                        dateChanged = true;
                    }

                    if (dateChanged) {
                        if (scrapedBid.getBidEndDate() != null) {
                            boolean isActive = scrapedBid.getBidEndDate().isAfter(LocalDateTime.now());
                            existingBid.setIsActive(isActive);
                            existingBid.setIsDeactive(!isActive);
                        }
                        savedBid = bidRepository.save(existingBid);
                        ctx.totalUpdatedCount.incrementAndGet();
                        log.info("📅 DATE UPDATED bid: {} - Only dates changed", existingBid.getBidNumber());
                    } else {
                        ctx.totalUnchangedCount.incrementAndGet();
                        log.debug("Bid {} unchanged, skipping", existingBid.getBidNumber());
                        return;
                    }
                }
            } else {
                scrapedBid.setIsFinalized(false);
                scrapedBid.setCreatedDate(LocalDateTime.now());

                if (scrapedBid.getBidEndDate() != null) {
                    scrapedBid.setIsActive(scrapedBid.getBidEndDate().isAfter(LocalDateTime.now()));
                    scrapedBid.setIsDeactive(!scrapedBid.getIsActive());
                } else {
                    scrapedBid.setIsActive(true);
                    scrapedBid.setIsDeactive(false);
                }

                savedBid = bidRepository.save(scrapedBid);
                ctx.totalNewCount.incrementAndGet();
                log.info("➕ NEW bid found: {}", scrapedBid.getBidNumber());
            }

            if (savedBid != null) {
                extractPdfSynchronously(savedBid, ctx);
            }

        } catch (DataIntegrityViolationException e) {
            log.debug("Bid {} already exists (duplicate)", scrapedBid.getBidNumber());
            ctx.totalUnchangedCount.incrementAndGet();
            throw e;
        } catch (Exception e) {
            log.error("Error processing bid {}: {}", scrapedBid.getBidNumber(), e.getMessage());
            ctx.totalFailedCount.incrementAndGet();
            throw new RuntimeException("Failed to process bid: " + scrapedBid.getBidNumber(), e);
        }
    }

    private void extractPdfSynchronously(Bid bid, JobContext ctx) {
        if (bid == null) return;

        long pdfStartTime = System.currentTimeMillis();
        log.info("📄 [SYNC] Starting PDF extraction for bid: {}", bid.getBidNumber());

        try {
            BidDetails details = bidDetailsService.extractBidDetailsImmediately(bid);

            if (details != null && "COMPLETED".equals(details.getExtractionStatus())) {
                ctx.totalPdfExtractedCount.incrementAndGet();
                long timeTaken = (System.currentTimeMillis() - pdfStartTime) / 1000;
                log.info("✅ [SYNC] PDF extracted successfully for bid: {} in {}s", bid.getBidNumber(), timeTaken);

                if (details.getPreBidDateTime() != null) {
                    log.info("   Pre-bid date found: {}", details.getPreBidDateTime());
                }
            } else {
                ctx.totalPdfFailedCount.incrementAndGet();
                log.warn("⚠️ [SYNC] PDF extraction failed/incomplete for bid: {}", bid.getBidNumber());
            }
        } catch (Exception e) {
            ctx.totalPdfFailedCount.incrementAndGet();
            log.error("❌ [SYNC] PDF extraction failed for bid {}: {}", bid.getBidNumber(), e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // WebDriver factory - identical to original
    // -------------------------------------------------------------------------
    private WebDriver createStealthWebDriver() {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");

        String viewport = VIEWPORT_SIZES[random.nextInt(VIEWPORT_SIZES.length)];
        options.addArguments("--window-size=" + viewport);

        String userAgent = USER_AGENTS[random.nextInt(USER_AGENTS.length)];
        options.addArguments("--user-agent=" + userAgent);

        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--disable-extensions");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--ignore-certificate-errors");
        options.addArguments("--disable-web-security");
        options.addArguments("--allow-running-insecure-content");

        options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        options.setExperimentalOption("useAutomationExtension", false);

        Map<String, Object> prefs = new HashMap<>();
        prefs.put("credentials_enable_service", false);
        prefs.put("profile.password_manager_enabled", false);
        prefs.put("profile.default_content_setting_values.notifications", 2);
        options.setExperimentalOption("prefs", prefs);

        WebDriver driver = new ChromeDriver(options);

        ((JavascriptExecutor) driver).executeScript(
            "Object.defineProperty(navigator, 'webdriver', {get: () => undefined})"
        );

        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(PAGE_LOAD_TIMEOUT));
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));

        return driver;
    }

    // -------------------------------------------------------------------------
    // Navigation helpers - unchanged from original
    // -------------------------------------------------------------------------
    private void navigateWithRetry(WebDriver driver, String url) {
        int retries = 0;
        while (retries < 3) {
            try {
                driver.get(url);
                return;
            } catch (TimeoutException e) {
                retries++;
                if (retries >= 3) throw e;
                log.warn("Timeout loading {}, retrying... ({}/3)", url, retries);
                sleepRandom(5000, 10000);
            }
        }
    }

    private void clickElementNaturally(WebDriver driver, By by) {
        try {
            WebElement element = waitForElement(driver, by, 10);
            ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});", element
            );
            sleepRandom(500, 1500);
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
        } catch (Exception e) {
            driver.findElement(by).click();
        }
    }

    private void selectDropdownNaturally(WebDriver driver, By by, String value) {
        WebElement dropdown = waitForElement(driver, by, 10);
        ((JavascriptExecutor) driver).executeScript(
            "arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});", dropdown
        );
        sleepRandom(500, 1500);
        Select select = new Select(dropdown);
        select.selectByVisibleText(value);
    }

    private void scrollNaturally(WebDriver driver) {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            long height = (Long) js.executeScript("return document.body.scrollHeight");
            int scrollPosition = random.nextInt((int) height);
            js.executeScript("window.scrollTo({top: " + scrollPosition + ", behavior: 'smooth'});");
            sleepRandom(1000, 3000);
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * Scrape all bids on the currently loaded page and process each one.
     */
    private void scrapeOnePage(WebDriver driver, State state, City city,
                                int pageNumber, int totalPages, JobContext ctx) {
        log.info("📄 Scraping page {}/{} - {}-{}", pageNumber, totalPages,
                state.getStateName(), city.getCityName());

        scrollNaturally(driver);

        List<Bid> bidsFromPage = scrapePageNaturally(driver, state, city);

        log.info("Found {} bids on page {}/{}", bidsFromPage.size(), pageNumber, totalPages);

        for (Bid bid : bidsFromPage) {
            try {
                processSingleBidWithPdf(bid, ctx);
                sleepRandom(2000, 4000);
            } catch (DataIntegrityViolationException e) {
                log.debug("Bid {} already exists, skipping", bid.getBidNumber());
                ctx.totalUnchangedCount.incrementAndGet();
            } catch (Exception e) {
                log.error("Error processing bid {}: {}", bid.getBidNumber(), e.getMessage());
                ctx.totalFailedCount.incrementAndGet();
            }
        }
    }

    private List<Bid> scrapePageNaturally(WebDriver driver, State state, City city) {
        List<Bid> pageBids = new ArrayList<>();

        try {
            List<WebElement> bidElements = driver.findElements(By.cssSelector("div.card"));

            for (WebElement element : bidElements) {
                try {
                    sleepRandom(1000, 3000);

                    ((JavascriptExecutor) driver).executeScript(
                        "arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});", element
                    );
                    sleepRandom(500, 1500);

                    Bid scrapedBid = extractBidFromSearchResult(element);
                    if (scrapedBid != null && scrapedBid.getBidNumber() != null) {
                        scrapedBid.setState(state);
                        scrapedBid.setConsigneeCity(city);
                        pageBids.add(scrapedBid);
                    }
                } catch (StaleElementReferenceException e) {
                    log.debug("Element became stale, skipping");
                }
            }
        } catch (Exception e) {
            log.error("Error scraping page: {}", e.getMessage());
        }

        return pageBids;
    }

    /**
     * Read the total number of pages from the pagination bar on the first page.
     * GeM portal shows: « Prev  1  2  3 ... 66  Next »
     * Returns 1 if pagination is absent (single page or no results).
     */
    private int getTotalPages(WebDriver driver) {
        try {
            // Wait a moment for pagination to render
            sleepRandom(1000, 2000);

            // Strategy 1: highest numeric link inside .pagination2
            List<WebElement> pageLinks = driver.findElements(By.cssSelector(".pagination2 a"));
            int maxPage = 0;
            for (WebElement link : pageLinks) {
                try {
                    String t = link.getText().trim();
                    if (t.matches("\\d+")) {
                        maxPage = Math.max(maxPage, Integer.parseInt(t));
                    }
                } catch (Exception ignored) { }
            }
            if (maxPage > 0) {
                log.info("📊 Total pages detected from pagination links: {}", maxPage);
                return maxPage;
            }

            // Strategy 2: span.current siblings
            List<WebElement> spans = driver.findElements(By.cssSelector(".pagination2 span"));
            for (WebElement span : spans) {
                try {
                    String t = span.getText().trim();
                    if (t.matches("\\d+")) {
                        maxPage = Math.max(maxPage, Integer.parseInt(t));
                    }
                } catch (Exception ignored) { }
            }
            if (maxPage > 0) {
                log.info("📊 Total pages detected from pagination spans: {}", maxPage);
                return maxPage;
            }

            // Strategy 3: parse page source for the last page number pattern
            String src = driver.getPageSource();
            // GeM uses goToPage(N) or page=N in pagination links
            Pattern p = Pattern.compile("goToPage\\((\\d+)\\)|[?&]page=(\\d+)");
            Matcher m = p.matcher(src);
            while (m.find()) {
                String val = m.group(1) != null ? m.group(1) : m.group(2);
                try { maxPage = Math.max(maxPage, Integer.parseInt(val)); } catch (Exception ignored) { }
            }
            if (maxPage > 0) {
                log.info("📊 Total pages detected from page source: {}", maxPage);
                return maxPage;
            }

            log.info("📊 Could not detect total pages, assuming 1");
            return 1;

        } catch (Exception e) {
            log.warn("Could not determine total pages: {}", e.getMessage());
            return 1;
        }
    }

    /**
     * Navigate to a specific page number using the GeM portal's JS pagination.
     * GeM renders results via goToPage(N) or equivalent – we invoke it directly
     * via JavaScript so empty-result pages never block navigation.
     *
     * Returns true if navigation was attempted, false only on hard error.
     */
    private boolean navigateToPage(WebDriver driver, int targetPage, int currentPage) {
        try {
            sleepRandom(2000, 4000);

            // ---- Approach 1: click the numbered link if visible ----
            try {
                List<WebElement> pageLinks = driver.findElements(By.cssSelector(".pagination2 a"));
                for (WebElement link : pageLinks) {
                    if (link.getText().trim().equals(String.valueOf(targetPage))) {
                        ((JavascriptExecutor) driver).executeScript(
                            "arguments[0].scrollIntoView({behavior:'smooth',block:'center'});", link);
                        sleepRandom(500, 1500);
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", link);
                        sleepRandom(5000, 9000);
                        log.debug("Navigated to page {} via numbered link", targetPage);
                        return true;
                    }
                }
            } catch (Exception ignored) { }

            // ---- Approach 2: click "Next" button ----
            try {
                List<WebElement> nextBtns = driver.findElements(By.cssSelector(".pagination2 a.next"));
                if (nextBtns.isEmpty()) nextBtns = driver.findElements(By.linkText("Next"));
                if (nextBtns.isEmpty()) nextBtns = driver.findElements(By.xpath("//a[contains(text(),'Next')]"));

                for (WebElement btn : nextBtns) {
                    String cls = btn.getAttribute("class");
                    String tag = btn.getTagName();
                    boolean disabled = (cls != null && (cls.contains("disabled") || cls.contains("current")))
                                       || "span".equalsIgnoreCase(tag);
                    if (!disabled && btn.isDisplayed()) {
                        ((JavascriptExecutor) driver).executeScript(
                            "arguments[0].scrollIntoView({behavior:'smooth',block:'center'});", btn);
                        sleepRandom(500, 1500);
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", btn);
                        sleepRandom(5000, 9000);
                        log.debug("Navigated to page {} via Next button", targetPage);
                        return true;
                    }
                }
            } catch (Exception ignored) { }

            // ---- Approach 3: call goToPage(N) directly via JS ----
            try {
                String pageSource = driver.getPageSource();
                // Detect the JS function name used by GeM for pagination
                // Common patterns: goToPage(n), changePage(n), loadPage(n)
                String jsFunc = null;
                if (pageSource.contains("goToPage(")) jsFunc = "goToPage";
                else if (pageSource.contains("changePage(")) jsFunc = "changePage";
                else if (pageSource.contains("loadPage(")) jsFunc = "loadPage";
                else if (pageSource.contains("gotopage(")) jsFunc = "gotopage";

                if (jsFunc != null) {
                    ((JavascriptExecutor) driver).executeScript(jsFunc + "(" + targetPage + ");");
                    sleepRandom(5000, 9000);
                    log.debug("Navigated to page {} via JS function {}()", targetPage, jsFunc);
                    return true;
                }
            } catch (Exception ignored) { }

            // ---- Approach 4: manipulate URL directly ----
            try {
                String currentUrl = driver.getCurrentUrl();
                String newUrl;
                if (currentUrl.contains("page=")) {
                    newUrl = currentUrl.replaceAll("page=\\d+", "page=" + targetPage);
                } else if (currentUrl.contains("?")) {
                    newUrl = currentUrl + "&page=" + targetPage;
                } else {
                    newUrl = currentUrl + "?page=" + targetPage;
                }
                if (!newUrl.equals(currentUrl)) {
                    driver.get(newUrl);
                    sleepRandom(5000, 9000);
                    log.debug("Navigated to page {} via URL manipulation", targetPage);
                    return true;
                }
            } catch (Exception ignored) { }

            log.warn("⚠️ Could not navigate to page {} from page {}", targetPage, currentPage);
            return false;

        } catch (Exception e) {
            log.error("Error navigating to page {}: {}", targetPage, e.getMessage());
            return false;
        }
    }

    /**
     * Verify which page we are currently on by reading the pagination bar.
     * Returns -1 if unable to determine.
     */
    private int getCurrentPageNumber(WebDriver driver) {
        try {
            // span.current with a number = current page indicator
            List<WebElement> currentSpans = driver.findElements(By.cssSelector(".pagination2 span.current"));
            for (WebElement span : currentSpans) {
                String t = span.getText().trim();
                if (t.matches("\\d+")) return Integer.parseInt(t);
            }
            // active/selected link
            List<WebElement> activeLinks = driver.findElements(
                By.cssSelector(".pagination2 a.active, .pagination2 a.selected, .pagination2 .active a"));
            for (WebElement link : activeLinks) {
                String t = link.getText().trim();
                if (t.matches("\\d+")) return Integer.parseInt(t);
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private boolean noResultsFound(WebDriver driver) {
        try {
            String pageSource = driver.getPageSource().toLowerCase();
            return pageSource.contains("no bids found")
                || pageSource.contains("no records found")
                || pageSource.contains("no data available")
                || pageSource.contains("no result found");
        } catch (Exception e) {
            return false;
        }
    }

    private WebElement waitForElement(WebDriver driver, By by, int maxSeconds) {
        int attempts = 0;
        int maxAttempts = maxSeconds * 2;

        while (attempts < maxAttempts) {
            try {
                return driver.findElement(by);
            } catch (NoSuchElementException e) {
                sleepRandom(300, 700);
                attempts++;
            }
        }
        throw new NoSuchElementException("Element not found: " + by);
    }

    private void sleepRandom(int minMs, int maxMs) {
        try {
            int sleepTime = minMs + random.nextInt(maxMs - minMs);
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // States & Cities bootstrap - unchanged from original
    // -------------------------------------------------------------------------
    private void ensureStatesAndCitiesPopulated() {
        if (stateRepository.count() == 0) {
            log.info("No states found. Scraping states and cities with stealth mode...");
            WebDriver tempDriver = null;
            try {
                tempDriver = createStealthWebDriver();
                scrapeStatesAndCities(tempDriver);
            } finally {
                if (tempDriver != null) {
                    try { tempDriver.quit(); } catch (Exception ignored) { }
                }
            }
        }
    }

    private void scrapeStatesAndCities(WebDriver driver) {
        try {
            log.info("Navigating to advance search page...");
            navigateWithRetry(driver, baseUrl + "/advance-search");
            sleepRandom(5000, 10000);

            clickElementNaturally(driver, By.cssSelector("a[href='#tab2']"));
            sleepRandom(2000, 4000);

            WebElement stateDropdown = driver.findElement(By.id("state_name_con"));
            Select stateSelect = new Select(stateDropdown);
            List<WebElement> stateOptions = stateSelect.getOptions();

            log.info("Found {} states in dropdown", stateOptions.size());

            int stateCount = 0;
            for (int i = 1; i < stateOptions.size(); i++) {
                String stateName = stateOptions.get(i).getText().trim();

                if (stateName.isEmpty() || stateName.equals("Select State")) {
                    continue;
                }

                log.info("Processing state: {}", stateName);

                Optional<State> existingState = stateRepository.findByStateName(stateName);
                State state;

                if (existingState.isPresent()) {
                    state = existingState.get();
                } else {
                    state = new State();
                    state.setStateName(stateName);
                    state.setIsActive(true);
                    state = stateRepository.save(state);
                    stateCount++;
                }

                selectDropdownNaturally(driver, By.id("state_name_con"), stateName);
                sleepRandom(3000, 6000);

                try {
                    WebElement cityDropdown = driver.findElement(By.id("city_name_con"));
                    Select citySelect = new Select(cityDropdown);
                    List<WebElement> cityOptions = citySelect.getOptions();

                    for (int j = 1; j < cityOptions.size(); j++) {
                        String cityName = cityOptions.get(j).getText().trim();

                        if (cityName.isEmpty() || cityName.equals("Select City")) {
                            continue;
                        }

                        Optional<City> existingCity = cityRepository.findByCityNameAndState(cityName, state);

                        if (existingCity.isEmpty()) {
                            City city = new City();
                            city.setCityName(cityName);
                            city.setState(state);
                            city.setIsActive(true);
                            cityRepository.save(city);
                            log.debug("Created city: {} for {}", cityName, stateName);
                            sleepRandom(500, 1500);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Could not load cities for state {}: {}", stateName, e.getMessage());
                }

                sleepRandom(5000, 10000);
            }

            log.info("✅ Scraped {} new states", stateCount);

        } catch (Exception e) {
            log.error("Error scraping states and cities: {}", e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Bid comparison helpers - unchanged from original
    // -------------------------------------------------------------------------
    private boolean hasBidChanged(Bid existing, Bid scraped) {
        if (!Objects.equals(existing.getRaNumber(), scraped.getRaNumber())) return true;
        if (!Objects.equals(existing.getBidDocumentUrl(), scraped.getBidDocumentUrl())) return true;
        if (!Objects.equals(existing.getRaDocumentUrl(), scraped.getRaDocumentUrl())) return true;
        if (!Objects.equals(existing.getItems(), scraped.getItems())) return true;
        if (!Objects.equals(existing.getDataContent(), scraped.getDataContent())) return true;
        if (!Objects.equals(existing.getQuantity(), scraped.getQuantity())) return true;
        if (!Objects.equals(existing.getDepartment(), scraped.getDepartment())) return true;
        if (!Objects.equals(existing.getMinistry(), scraped.getMinistry())) return true;
        if (!Objects.equals(existing.getBidType(), scraped.getBidType())) return true;

        if (existing.getState() != null && scraped.getState() != null) {
            if (!Objects.equals(existing.getState().getId(), scraped.getState().getId())) return true;
        } else if (existing.getState() != null || scraped.getState() != null) {
            return true;
        }

        if (existing.getConsigneeCity() != null && scraped.getConsigneeCity() != null) {
            if (!Objects.equals(existing.getConsigneeCity().getId(), scraped.getConsigneeCity().getId())) return true;
        } else if (existing.getConsigneeCity() != null || scraped.getConsigneeCity() != null) {
            return true;
        }

        return false;
    }

    private boolean updateExistingBid(Bid existingBid, Bid scrapedBid) {
        boolean updated = false;

        if (scrapedBid.getRaNumber() != null && !scrapedBid.getRaNumber().equals(existingBid.getRaNumber())) {
            existingBid.setRaNumber(scrapedBid.getRaNumber()); updated = true;
        }
        if (scrapedBid.getBidDocumentUrl() != null && !scrapedBid.getBidDocumentUrl().equals(existingBid.getBidDocumentUrl())) {
            existingBid.setBidDocumentUrl(scrapedBid.getBidDocumentUrl()); updated = true;
        }
        if (scrapedBid.getRaDocumentUrl() != null && !scrapedBid.getRaDocumentUrl().equals(existingBid.getRaDocumentUrl())) {
            existingBid.setRaDocumentUrl(scrapedBid.getRaDocumentUrl()); updated = true;
        }
        if (scrapedBid.getItems() != null && !scrapedBid.getItems().isEmpty() && !scrapedBid.getItems().equals(existingBid.getItems())) {
            existingBid.setItems(scrapedBid.getItems()); updated = true;
        }
        if (scrapedBid.getDataContent() != null && !scrapedBid.getDataContent().isEmpty() && !scrapedBid.getDataContent().equals(existingBid.getDataContent())) {
            existingBid.setDataContent(scrapedBid.getDataContent()); updated = true;
        }
        if (scrapedBid.getQuantity() != null && !scrapedBid.getQuantity().equals(existingBid.getQuantity())) {
            existingBid.setQuantity(scrapedBid.getQuantity()); updated = true;
        }
        if (scrapedBid.getDepartment() != null && !scrapedBid.getDepartment().equals(existingBid.getDepartment())) {
            existingBid.setDepartment(scrapedBid.getDepartment()); updated = true;
        }
        if (scrapedBid.getMinistry() != null && !scrapedBid.getMinistry().equals(existingBid.getMinistry())) {
            existingBid.setMinistry(scrapedBid.getMinistry()); updated = true;
        }
        if (scrapedBid.getBidStartDate() != null && !scrapedBid.getBidStartDate().equals(existingBid.getBidStartDate())) {
            existingBid.setBidStartDate(scrapedBid.getBidStartDate()); updated = true;
        }
        if (scrapedBid.getBidEndDate() != null && !scrapedBid.getBidEndDate().equals(existingBid.getBidEndDate())) {
            existingBid.setBidEndDate(scrapedBid.getBidEndDate()); updated = true;
        }
        if (scrapedBid.getRaStartDate() != null && !scrapedBid.getRaStartDate().equals(existingBid.getRaStartDate())) {
            existingBid.setRaStartDate(scrapedBid.getRaStartDate()); updated = true;
        }
        if (scrapedBid.getRaEndDate() != null && !scrapedBid.getRaEndDate().equals(existingBid.getRaEndDate())) {
            existingBid.setRaEndDate(scrapedBid.getRaEndDate()); updated = true;
        }
        if (scrapedBid.getBidType() != null && !scrapedBid.getBidType().equals(existingBid.getBidType())) {
            existingBid.setBidType(scrapedBid.getBidType()); updated = true;
        }

        return updated;
    }

    // -------------------------------------------------------------------------
    // Bid extraction from HTML - unchanged from original
    // -------------------------------------------------------------------------
    private Bid extractBidFromSearchResult(WebElement element) {
        Bid bid = new Bid();

        try {
            String fullText = element.getText();

            List<WebElement> links = element.findElements(By.cssSelector("a.bid_no_hover"));
            for (WebElement link : links) {
                String href = link.getAttribute("href");
                String linkText = link.getText().trim();

                if (linkText.contains("/B/") || linkText.matches("GEM/\\d{4}/B/\\d+")) {
                    bid.setBidNumber(linkText);
                    if (href != null) {
                        bid.setBidDocumentUrl(href.startsWith("http") ? href : baseUrl + href);
                    }
                }
            }

            if (bid.getBidNumber() == null) {
                Pattern bidPattern = Pattern.compile("(GEM/\\d{4}/[B|R]/\\d+)");
                Matcher bidMatcher = bidPattern.matcher(fullText);
                if (bidMatcher.find()) {
                    bid.setBidNumber(bidMatcher.group(1));
                } else {
                    return null;
                }
            }

            Pattern raPattern = Pattern.compile("(GEM/\\d{4}/R/\\d+)");
            Matcher raMatcher = raPattern.matcher(fullText);
            if (raMatcher.find()) {
                bid.setRaNumber(raMatcher.group(1));
            }

            try {
                List<WebElement> itemElements = element.findElements(By.cssSelector("a[data-toggle='popover']"));
                if (!itemElements.isEmpty()) {
                    String itemText = itemElements.get(0).getText().trim();
                    if (itemText != null && !itemText.isEmpty()) {
                        bid.setItems(itemText);
                    }
                }
                if (bid.getItems() == null || bid.getItems().isEmpty()) {
                    Pattern itemsPattern = Pattern.compile(
                        "Items?:?\\s*(.+?)(?=Quantity:|Department|Ministry|Start Date:|End Date:|$)",
                        Pattern.DOTALL);
                    Matcher itemsMatcher = itemsPattern.matcher(fullText);
                    if (itemsMatcher.find()) {
                        String items = itemsMatcher.group(1).trim();
                        if (!items.isEmpty() && !items.equals("BID")) {
                            bid.setItems(items);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Items extraction failed: {}", e.getMessage());
            }

            try {
                List<WebElement> dataElements = element.findElements(By.cssSelector("a[data-toggle='popover']"));
                if (!dataElements.isEmpty()) {
                    String dataContent = dataElements.get(0).getAttribute("data-content");
                    if (dataContent != null && !dataContent.isEmpty()) {
                        bid.setDataContent(dataContent);
                    }
                }
                if (bid.getDataContent() == null || bid.getDataContent().isEmpty()) {
                    List<WebElement> allDataElements = element.findElements(By.cssSelector("[data-content]"));
                    for (WebElement dataElement : allDataElements) {
                        String dataContent = dataElement.getAttribute("data-content");
                        if (dataContent != null && !dataContent.isEmpty()) {
                            bid.setDataContent(dataContent);
                            break;
                        }
                    }
                }
                if (bid.getDataContent() == null || bid.getDataContent().isEmpty()) {
                    Pattern dataPattern = Pattern.compile(
                        "Description:?\\s*(.+?)(?=Quantity:|Department|Ministry|Start Date:|End Date:|$)",
                        Pattern.DOTALL);
                    Matcher dataMatcher = dataPattern.matcher(fullText);
                    if (dataMatcher.find()) {
                        String dataContent = dataMatcher.group(1).trim();
                        if (!dataContent.isEmpty()) {
                            bid.setDataContent(dataContent);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Data content extraction failed: {}", e.getMessage());
            }

            try {
                Pattern quantityPattern = Pattern.compile("Quantity:?\\s*(\\d+(?:,\\d+)?(?:\\.\\d+)?)");
                Matcher quantityMatcher = quantityPattern.matcher(fullText);
                if (quantityMatcher.find()) {
                    bid.setQuantity(quantityMatcher.group(1).trim().replace(",", ""));
                }
            } catch (Exception e) {
                log.debug("Quantity extraction failed");
            }

            try {
                Pattern deptPattern = Pattern.compile("(Department of [^\\n]+)");
                Matcher deptMatcher = deptPattern.matcher(fullText);
                if (deptMatcher.find()) {
                    bid.setDepartment(deptMatcher.group(1).trim());
                }
                Pattern ministryPattern = Pattern.compile("(Ministry of [^\\n]+)");
                Matcher ministryMatcher = ministryPattern.matcher(fullText);
                if (ministryMatcher.find()) {
                    bid.setMinistry(ministryMatcher.group(1).trim());
                }
            } catch (Exception e) {
                log.debug("Department/Ministry extraction failed");
            }

            try {
                Pattern datePattern = Pattern.compile("(\\d{2}-\\d{2}-\\d{4}\\s+\\d{1,2}:\\d{2}\\s+[AP]M)");
                Matcher dateMatcher = datePattern.matcher(fullText);
                List<String> dates = new ArrayList<>();
                while (dateMatcher.find()) {
                    dates.add(dateMatcher.group(1));
                }
                if (dates.size() >= 2) {
                    bid.setBidStartDate(parseGeMDate(dates.get(0)));
                    bid.setBidEndDate(parseGeMDate(dates.get(1)));
                }
                if (dates.size() >= 4) {
                    bid.setRaStartDate(parseGeMDate(dates.get(2)));
                    bid.setRaEndDate(parseGeMDate(dates.get(3)));
                }
            } catch (Exception e) {
                log.debug("Date extraction failed");
            }

            bid.setBidType((bid.getRaNumber() != null && !bid.getRaNumber().isEmpty()) ? "BID_TO_RA" : "BID_ONLY");

            return bid;

        } catch (StaleElementReferenceException e) {
            log.debug("Element became stale, skipping");
            return null;
        } catch (Exception e) {
            log.error("Error extracting bid: {}", e.getMessage());
            return null;
        }
    }

    private LocalDateTime parseGeMDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return null;

        try {
            dateStr = dateStr.replace("IST", "").trim().replaceAll("\\s+", " ");
            try {
                return LocalDateTime.parse(dateStr, GEM_DATE_FORMATTER);
            } catch (DateTimeParseException e) {
                try {
                    return LocalDateTime.parse(dateStr, GEM_DATE_FORMATTER_2);
                } catch (DateTimeParseException e2) {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
                    String withoutAmPm = dateStr.replaceAll("[AP]M", "").trim();
                    return LocalDateTime.parse(withoutAmPm, formatter);
                }
            }
        } catch (Exception e) {
            log.debug("Date parsing failed for: {}", dateStr);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Progress logger
    // -------------------------------------------------------------------------
    private void startProgressLogger(String mode, JobContext ctx) {
        progressLogger = Executors.newSingleThreadScheduledExecutor();
        progressLogger.scheduleAtFixedRate(() ->
            log.info("[{}] Progress - New: {}, Updated: {}, Unchanged: {}, Reactivated: {}, Failed: {}, PDF Success: {}, PDF Failed: {}",
                mode, ctx.totalNewCount.get(), ctx.totalUpdatedCount.get(), ctx.totalUnchangedCount.get(),
                ctx.totalReactivatedCount.get(), ctx.totalFailedCount.get(),
                ctx.totalPdfExtractedCount.get(), ctx.totalPdfFailedCount.get()),
            1, 5, TimeUnit.MINUTES);
    }

    private void stopProgressLogger() {
        if (progressLogger != null) {
            progressLogger.shutdown();
            try {
                progressLogger.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                progressLogger.shutdownNow();
            }
        }
    }

    // -------------------------------------------------------------------------
    // JobContext - scoped counters per scraping job (fixes the shared-state bug)
    // -------------------------------------------------------------------------
    public static class JobContext {
        public final AtomicInteger totalNewCount        = new AtomicInteger(0);
        public final AtomicInteger totalUpdatedCount    = new AtomicInteger(0);
        public final AtomicInteger totalUnchangedCount  = new AtomicInteger(0);
        public final AtomicInteger totalFailedCount     = new AtomicInteger(0);
        public final AtomicInteger totalReactivatedCount = new AtomicInteger(0);
        public final AtomicInteger totalPdfExtractedCount = new AtomicInteger(0);
        public final AtomicInteger totalPdfFailedCount  = new AtomicInteger(0);
    }

    // -------------------------------------------------------------------------
    // ScrapingResult - unchanged public API
    // -------------------------------------------------------------------------
    public static class ScrapingResult {
        private int totalNewCount = 0;
        private int totalUpdatedCount = 0;
        private int totalUnchangedCount = 0;
        private int totalFailedCount = 0;
        private int totalReactivatedCount = 0;
        private int totalPdfExtracted = 0;
        private int totalPdfFailed = 0;
        private long timeTakenSeconds = 0;
        private int pagesScraped = 0;
        private int currentPage = 0;
        private String errorMessage;

        public int getTotalNewCount() { return totalNewCount; }
        public void setTotalNewCount(int v) { this.totalNewCount = v; }
        public int getTotalUpdatedCount() { return totalUpdatedCount; }
        public void setTotalUpdatedCount(int v) { this.totalUpdatedCount = v; }
        public int getTotalUnchangedCount() { return totalUnchangedCount; }
        public void setTotalUnchangedCount(int v) { this.totalUnchangedCount = v; }
        public int getTotalFailedCount() { return totalFailedCount; }
        public void setTotalFailedCount(int v) { this.totalFailedCount = v; }
        public int getTotalReactivatedCount() { return totalReactivatedCount; }
        public void setTotalReactivatedCount(int v) { this.totalReactivatedCount = v; }
        public int getTotalPdfExtracted() { return totalPdfExtracted; }
        public void setTotalPdfExtracted(int v) { this.totalPdfExtracted = v; }
        public int getTotalPdfFailed() { return totalPdfFailed; }
        public void setTotalPdfFailed(int v) { this.totalPdfFailed = v; }
        public long getTimeTakenSeconds() { return timeTakenSeconds; }
        public void setTimeTakenSeconds(long v) { this.timeTakenSeconds = v; }
        public int getPagesScraped() { return pagesScraped; }
        public void setPagesScraped(int v) { this.pagesScraped = v; }
        public int getCurrentPage() { return currentPage; }
        public void setCurrentPage(int v) { this.currentPage = v; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String v) { this.errorMessage = v; }
    }
}