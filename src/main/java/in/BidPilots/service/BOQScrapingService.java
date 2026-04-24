package in.BidPilots.service;

import in.BidPilots.entity.Boq;
import in.BidPilots.repository.BoqRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.github.bonigarcia.wdm.WebDriverManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class BOQScrapingService {

    private final BoqRepository boqRepository;

    @Value("${gem.portal.base.url:https://bidplus.gem.gov.in}")
    private String baseUrl;

    private static final int BATCH_SIZE = 500; // Reduced batch size
    private static final int PAGE_LOAD_TIMEOUT = 60; // Increased timeout
    private static final int MAX_RETRIES = 5; // More retries
    
    // Anti-detection settings
    private static final String[] USER_AGENTS = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"
    };

    private final Random random = new Random();

    private final AtomicInteger totalFound = new AtomicInteger(0);
    private final AtomicInteger totalSaved = new AtomicInteger(0);
    private final AtomicInteger totalSkipped = new AtomicInteger(0);
    private volatile boolean isScrapingActive = false;
    private volatile String currentStatus = "Idle";
    private volatile int totalInDropdown = 0;
    private volatile LocalDateTime lastRunTime = null;

    public Map<String, Object> scrapeAllBoqs() {
        Map<String, Object> result = new HashMap<>();

        if (isScrapingActive) {
            result.put("success", false);
            result.put("message", "BOQ scraping already in progress");
            return result;
        }

        isScrapingActive = true;
        currentStatus = "Starting BOQ extraction...";
        totalFound.set(0);
        totalSaved.set(0);
        totalSkipped.set(0);
        totalInDropdown = 0;

        long startTime = System.currentTimeMillis();

        log.info("=".repeat(90));
        log.info("🚀 BOQ SCRAPING SERVICE STARTED");
        log.info("   Target URL : {}/advance-search", baseUrl);
        log.info("   Batch size : {} rows per DB flush", BATCH_SIZE);
        log.info("=".repeat(90));

        WebDriver driver = null;
        try {
            driver = createDriver();
            
            // Random delay before starting (anti-detection)
            int delay = random.nextInt(5000, 15000);
            log.info("⏳ Waiting {} seconds before starting...", delay/1000);
            Thread.sleep(delay);

            // Load page with better handling
            currentStatus = "Loading advance-search page...";
            if (!loadPageWithRetry(driver, baseUrl + "/advance-search")) {
                throw new RuntimeException("Failed to load GeM portal after " + MAX_RETRIES + " attempts");
            }
            
            // Wait for page to be fully loaded
            Thread.sleep(3000);

            // Activate BOQ section
            currentStatus = "Activating BOQ section...";
            if (!activateBoqSection(driver)) {
                log.warn("Could not find BOQ section, trying to find dropdown directly...");
            }
            Thread.sleep(2000);

            // Find dropdown
            currentStatus = "Locating BOQ dropdown...";
            WebElement dropdown = findBoqDropdown(driver);
            if (dropdown == null) {
                // Try to refresh and find again
                log.warn("Dropdown not found, refreshing page...");
                driver.navigate().refresh();
                Thread.sleep(3000);
                dropdown = findBoqDropdown(driver);
                if (dropdown == null) {
                    throw new RuntimeException("BOQ Title dropdown not found after refresh");
                }
            }

            // Get all options
            currentStatus = "Reading BOQ options...";
            Select select = new Select(dropdown);
            List<WebElement> options = select.getOptions();
            totalInDropdown = options.size() - 1;
            log.info("✅ Found {} BOQ entries in dropdown", totalInDropdown);

            if (totalInDropdown == 0) {
                throw new RuntimeException("No BOQ entries found");
            }

            // Load existing entries for duplicate detection
            currentStatus = "Loading existing data from database...";
            Set<String> existingGemIds = new HashSet<>(boqRepository.findAllGemBoqIds());
            Set<String> existingTitles = boqRepository.findAllBoqTitlesLowercase();
            log.info("   Existing in DB: {} by ID, {} by title", existingGemIds.size(), existingTitles.size());

            // Process entries in smaller batches
            List<Boq> batch = new ArrayList<>();
            int processed = 0;

            for (int i = 1; i < options.size(); i++) {
                try {
                    WebElement opt = options.get(i);
                    String title = opt.getText().trim();
                    String gemId = opt.getAttribute("value");

                    if (title.isEmpty() || title.equalsIgnoreCase("--Select--")) {
                        continue;
                    }

                    processed++;
                    totalFound.incrementAndGet();

                    // Check for duplicate
                    boolean isDuplicate = false;
                    if (gemId != null && !gemId.isBlank() && gemId.length() > 0 && gemId.length() < 50) {
                        isDuplicate = existingGemIds.contains(gemId);
                    } else {
                        isDuplicate = existingTitles.contains(title.toLowerCase());
                    }

                    if (!isDuplicate) {
                        Boq boq = Boq.builder()
                                .boqTitle(title)
                                .gemBoqId((gemId != null && !gemId.isBlank() && gemId.length() > 0 && gemId.length() < 50) ? gemId : null)
                                .isActive(true)
                                .build();
                        batch.add(boq);
                        
                        if (boq.getGemBoqId() != null) {
                            existingGemIds.add(boq.getGemBoqId());
                        } else {
                            existingTitles.add(title.toLowerCase());
                        }
                        
                        totalSaved.incrementAndGet();
                    } else {
                        totalSkipped.incrementAndGet();
                    }

                    // Progress logging
                    if (processed % 200 == 0 || processed == 1) {
                        double pct = (processed * 100.0) / totalInDropdown;
                        log.info("📊 Progress: {}/{} ({:.1f}%) | New: {} | Skipped: {}", 
                            processed, totalInDropdown, pct, totalSaved.get(), totalSkipped.get());
                        currentStatus = String.format("Processing %d/%d (%.1f%%)", processed, totalInDropdown, pct);
                    }

                    // Save batch
                    if (batch.size() >= BATCH_SIZE) {
                        saveBatch(batch);
                        batch.clear();
                        Thread.sleep(500); // Delay between batches
                    }
                    
                } catch (StaleElementReferenceException e) {
                    log.warn("Stale element at index {}, refreshing options...", i);
                    options = new Select(dropdown).getOptions();
                } catch (Exception e) {
                    log.warn("Error processing entry {}: {}", i, e.getMessage());
                }
            }

            // Save remaining
            if (!batch.isEmpty()) {
                saveBatch(batch);
            }

            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            lastRunTime = LocalDateTime.now();
            
            log.info("=".repeat(90));
            log.info("✅ BOQ SCRAPING COMPLETED");
            log.info("   Total in dropdown : {}", totalInDropdown);
            log.info("   New entries saved  : {}", totalSaved.get());
            log.info("   Entries skipped    : {}", totalSkipped.get());
            log.info("   Time taken         : {} min {} sec", elapsed / 60, elapsed % 60);
            log.info("=".repeat(90));

            result.put("success", true);
            result.put("totalInDropdown", totalInDropdown);
            result.put("boqSaved", totalSaved.get());
            result.put("boqSkipped", totalSkipped.get());
            result.put("timeTakenSeconds", elapsed);
            result.put("lastRun", lastRunTime.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")));
            currentStatus = "Completed";

        } catch (Exception e) {
            log.error("BOQ scraping failed: {}", e.getMessage(), e);
            currentStatus = "Failed: " + e.getMessage();
            result.put("success", false);
            result.put("message", "Scraping failed: " + e.getMessage());
        } finally {
            isScrapingActive = false;
            if (driver != null) {
                try {
                    driver.quit();
                    log.info("WebDriver closed");
                } catch (Exception ignored) {}
            }
        }

        return result;
    }

    private void saveBatch(List<Boq> batch) {
        try {
            boqRepository.saveAll(batch);
            log.info("💾 Saved batch of {} BOQ entries", batch.size());
        } catch (Exception e) {
            log.error("Failed to save batch: {}", e.getMessage());
            int savedCount = 0;
            for (Boq boq : batch) {
                try {
                    if (!boqRepository.existsByBoqTitleIgnoreCase(boq.getBoqTitle())) {
                        boqRepository.save(boq);
                        savedCount++;
                    }
                } catch (Exception ex) {
                    log.debug("Could not save: {} - {}", boq.getBoqTitle(), ex.getMessage());
                }
            }
            log.info("💾 Saved {} out of {} entries individually", savedCount, batch.size());
        }
    }

    public Map<String, Object> getProgress() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("isActive", isScrapingActive);
        p.put("isScrapingActive", isScrapingActive);
        p.put("status", currentStatus);
        p.put("currentStatus", currentStatus);
        p.put("totalInDropdown", totalInDropdown);
        p.put("totalFound", totalFound.get());
        p.put("totalSaved", totalSaved.get());
        p.put("totalSkipped", totalSkipped.get());
        p.put("boqFound", totalFound.get());
        p.put("boqSaved", totalSaved.get());
        p.put("boqSkipped", totalSkipped.get());
        p.put("percentage", totalInDropdown > 0
                ? Math.round(totalFound.get() * 1000.0 / totalInDropdown) / 10.0
                : 0);
        p.put("lastRun", lastRunTime != null ? lastRunTime.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")) : null);
        return p;
    }

    private WebDriver createDriver() {
        WebDriverManager.chromedriver().setup();

        ChromeOptions opts = new ChromeOptions();
        
        // Try non-headless first for debugging, then switch to headless
        // For production, use headless
        opts.addArguments("--headless=new");
        
        opts.addArguments("--no-sandbox");
        opts.addArguments("--disable-dev-shm-usage");
        opts.addArguments("--disable-gpu");
        opts.addArguments("--disable-software-rasterizer");
        opts.addArguments("--disable-extensions");
        opts.addArguments("--disable-setuid-sandbox");
        opts.addArguments("--remote-allow-origins=*");
        opts.addArguments("--window-size=1920,1080");
        opts.addArguments("--user-agent=" + USER_AGENTS[random.nextInt(USER_AGENTS.length)]);
        
        // Additional options to avoid detection
        opts.addArguments("--disable-blink-features=AutomationControlled");
        opts.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        opts.setExperimentalOption("useAutomationExtension", false);
        
        // Page load strategy
        opts.setPageLoadStrategy(PageLoadStrategy.NORMAL);

        WebDriver driver = new ChromeDriver(opts);
        
        // Set timeouts
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(PAGE_LOAD_TIMEOUT));
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
        
        return driver;
    }

    private boolean loadPageWithRetry(WebDriver driver, String url) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("Loading page attempt {}/{}", attempt, MAX_RETRIES);
                driver.get(url);
                
                // Wait for page to load
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
                wait.until(webDriver -> ((JavascriptExecutor) webDriver)
                    .executeScript("return document.readyState").equals("complete"));
                
                log.info("Page loaded successfully");
                return true;
            } catch (TimeoutException e) {
                log.warn("Timeout on attempt {}/{}: {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(5000 * attempt); // Increasing delay
                    } catch (InterruptedException ie) {}
                }
            } catch (Exception e) {
                log.warn("Error on attempt {}/{}: {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {}
                }
            }
        }
        return false;
    }

    private boolean activateBoqSection(WebDriver driver) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        
        // Try multiple selectors
        String[] selectors = {
            "//a[contains(text(),'BOQ')]",
            "//button[contains(text(),'BOQ')]",
            "//span[contains(text(),'BOQ')]",
            "//div[contains(text(),'BOQ')]",
            "//input[@value='BOQ']",
            "//label[contains(text(),'BOQ')]",
            "//*[contains(@class,'boq')]",
            "//*[contains(@id,'boq')]"
        };

        for (String xpath : selectors) {
            try {
                WebElement el = wait.until(ExpectedConditions.elementToBeClickable(By.xpath(xpath)));
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", el);
                log.info("Activated BOQ section via: {}", xpath);
                return true;
            } catch (Exception ignored) {}
        }
        
        log.warn("Could not find BOQ section, continuing...");
        return false;
    }

    private WebElement findBoqDropdown(WebDriver driver) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
        
        String[] selectors = {
            "//select[contains(@id,'boq')]",
            "//select[contains(@name,'boq')]",
            "//select[contains(@id,'BOQ')]",
            "//select[contains(@name,'BOQ')]",
            "//select[contains(@class,'boq')]",
            "//select"
        };

        for (String xpath : selectors) {
            try {
                WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(By.xpath(xpath)));
                if (element.isDisplayed() && element.isEnabled()) {
                    log.info("Found dropdown via: {}", xpath);
                    return element;
                }
            } catch (Exception ignored) {}
        }

        // Last resort: find any select element
        List<WebElement> selects = driver.findElements(By.tagName("select"));
        for (WebElement sel : selects) {
            try {
                List<WebElement> opts = sel.findElements(By.tagName("option"));
                if (opts.size() > 50) {
                    log.info("Found dropdown with {} options", opts.size());
                    return sel;
                }
            } catch (Exception ignored) {}
        }
        
        return null;
    }
}