package in.BidPilots.service;

import in.BidPilots.entity.Category;
import in.BidPilots.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.Select;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.bonigarcia.wdm.WebDriverManager;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class CategoryScrapingService {

    private final CategoryRepository categoryRepository;

    @Value("${gem.portal.base.url:https://bidplus.gem.gov.in}")
    private String baseUrl;

    // OPTIMIZED FOR SPEED - 13,364 categories in ~2-3 minutes
    private static final int BATCH_SIZE = 1000;        // Save 1000 at a time
    private static final int PAGE_LOAD_TIMEOUT = 30;
    
    // Minimal delays - just enough to avoid detection
    private static final int MIN_DELAY_MS = 10;         // 10ms between categories
    private static final int MAX_DELAY_MS = 50;         // 50ms max delay
    private static final int BATCH_DELAY_MS = 500;      // 0.5 sec between batches

    // Rotating user agents (keep for anti-detection)
    private static final String[] USER_AGENTS = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Safari/605.1.15"
    };

    // Viewport sizes
    private static final String[] VIEWPORT_SIZES = { "1920,1080", "1366,768", "1536,864" };

    private final Random random = new Random();

    // Progress tracking
    private final AtomicInteger totalCategoriesFound = new AtomicInteger(0);
    private final AtomicInteger totalCategoriesSaved = new AtomicInteger(0);
    private final AtomicInteger totalCategoriesSkipped = new AtomicInteger(0);
    private volatile boolean isScrapingActive = false;
    private volatile String currentStatus = "Idle";
    private int totalCategories = 0;

    /**
     * FAST Scrape all categories from GeM portal
     * Optimized to handle 13,364 categories in minutes
     */
    @Transactional
    public Map<String, Object> scrapeAllCategories() {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();

        if (isScrapingActive) {
            result.put("success", false);
            result.put("message", "Category scraping already in progress");
            return result;
        }

        isScrapingActive = true;
        currentStatus = "Starting fast category extraction...";
        totalCategoriesFound.set(0);
        totalCategoriesSaved.set(0);
        totalCategoriesSkipped.set(0);

        log.info("=".repeat(100));
        log.info("🚀 FAST CATEGORY SCRAPING SERVICE STARTED");
        log.info("=".repeat(100));
        log.info("   Mode: OPTIMIZED FOR SPEED");
        log.info("   Batch size: {} categories per save", BATCH_SIZE);
        log.info("   Delay between categories: {}ms", MAX_DELAY_MS);
        log.info("   Target: ~13,364 categories");
        log.info("   Estimated time: 2-3 minutes");
        log.info("=".repeat(100));

        WebDriver driver = null;

        try {
            driver = createStealthWebDriver();

            // Navigate to advance search page
            log.info("Navigating to advance search page...");
            navigateWithRetry(driver, baseUrl + "/advance-search");
            sleepRandom(2000, 3000); // Reduced initial delay

            // Find category dropdown
            WebElement categoryDropdown = findCategoryDropdown(driver);
            
            if (categoryDropdown == null) {
                throw new RuntimeException("Could not find category dropdown on the page");
            }

            // Get all options at once
            Select select = new Select(categoryDropdown);
            List<WebElement> options = select.getOptions();

            totalCategories = options.size() - 1; // Subtract the "--Select--" option
            log.info("Found {} categories in dropdown", totalCategories);

            // Pre-load existing categories to avoid DB checks
            Set<String> existingCategories = new HashSet<>(categoryRepository.findAllCategoryNames());
            log.info("Loaded {} existing categories from database", existingCategories.size());

            // Process in optimized batches
            List<Category> batchList = new ArrayList<>();
            
            for (int i = 1; i < options.size(); i++) {
                WebElement option = options.get(i);
                String categoryName = option.getText().trim();

                if (categoryName.isEmpty() || categoryName.equals("--Select--")) {
                    continue;
                }

                totalCategoriesFound.incrementAndGet();

                // Fast progress logging (every 1000 categories)
                if (i % 1000 == 0 || i == 1) {
                    double percentage = (i * 100.0) / options.size();
                    log.info("⚡ Progress: {}/{} categories ({:.1f}%) - New: {}, Existing: {}", 
                            i, options.size() - 1, percentage,
                            totalCategoriesSaved.get(), totalCategoriesSkipped.get());
                    
                    currentStatus = String.format("Processing %d/%d (%.1f%%)", i, options.size() - 1, percentage);
                }

                // Fast check if category exists (using HashSet)
                if (!existingCategories.contains(categoryName)) {
                    Category category = new Category();
                    category.setCategoryName(categoryName);
                    category.setIsActive(true);
                    category.setIsDeactive(false);
                    
                    batchList.add(category);
                    existingCategories.add(categoryName); // Add to set to prevent duplicates in this batch
                    totalCategoriesSaved.incrementAndGet();
                } else {
                    totalCategoriesSkipped.incrementAndGet();
                }

                // Save in large batches
                if (batchList.size() >= BATCH_SIZE) {
                    categoryRepository.saveAll(batchList);
                    categoryRepository.flush();
                    log.debug("Batch of {} categories saved. Total saved: {}", batchList.size(), totalCategoriesSaved.get());
                    batchList.clear();
                    
                    // Short break between batches
                    if (i < options.size() - 1) {
                        Thread.sleep(BATCH_DELAY_MS);
                    }
                }

                // Minimal delay between categories
                if (i % 100 != 0) { // Skip delay every 100 items to speed up
                    Thread.sleep(MIN_DELAY_MS + random.nextInt(MAX_DELAY_MS - MIN_DELAY_MS));
                }
            }

            // Save any remaining categories
            if (!batchList.isEmpty()) {
                categoryRepository.saveAll(batchList);
                categoryRepository.flush();
                log.debug("Final batch of {} categories saved", batchList.size());
            }

            long timeTaken = (System.currentTimeMillis() - startTime) / 1000;
            long minutes = timeTaken / 60;
            long seconds = timeTaken % 60;

            log.info("=".repeat(100));
            log.info("✅ FAST CATEGORY SCRAPING COMPLETED");
            log.info("=".repeat(100));
            log.info("   Total Categories Found: {}", totalCategoriesFound.get());
            log.info("   New Categories Saved: {}", totalCategoriesSaved.get());
            log.info("   Existing Categories Skipped: {}", totalCategoriesSkipped.get());
            log.info("   Time Taken: {} minutes {} seconds", minutes, seconds);
            log.info("=".repeat(100));

            result.put("success", true);
            result.put("message", "Categories scraped successfully");
            result.put("categoriesFound", totalCategoriesFound.get());
            result.put("categoriesSaved", totalCategoriesSaved.get());
            result.put("categoriesSkipped", totalCategoriesSkipped.get());
            result.put("timeTakenSeconds", timeTaken);
            result.put("timeTakenFormatted", String.format("%d min %d sec", minutes, seconds));
            currentStatus = "Completed";

            return result;

        } catch (Exception e) {
            log.error("Error in category scraping: {}", e.getMessage(), e);
            currentStatus = "Failed: " + e.getMessage();
            result.put("success", false);
            result.put("message", "Scraping failed: " + e.getMessage());
            result.put("error", e.getMessage());
            return result;
        } finally {
            isScrapingActive = false;
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception e) {
                    log.error("Error closing driver: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Find category dropdown element - OPTIMIZED
     */
    private WebElement findCategoryDropdown(WebDriver driver) {
        // Try most likely ID first
        try {
            return driver.findElement(By.id("categorybid"));
        } catch (NoSuchElementException e) {
            // Continue
        }

        // Try by name
        try {
            return driver.findElement(By.name("category"));
        } catch (NoSuchElementException e) {
            // Continue
        }

        // Try XPath as last resort
        try {
            return driver.findElement(By.xpath("//select[contains(@id, 'category')]"));
        } catch (NoSuchElementException e) {
            return null;
        }
    }

    /**
     * Get scraping progress
     */
    public Map<String, Object> getProgress() {
        Map<String, Object> progress = new HashMap<>();
        progress.put("isActive", isScrapingActive);
        progress.put("status", currentStatus);
        progress.put("categoriesFound", totalCategoriesFound.get());
        progress.put("categoriesSaved", totalCategoriesSaved.get());
        progress.put("categoriesSkipped", totalCategoriesSkipped.get());
        progress.put("totalCategories", totalCategories);
        
        if (totalCategories > 0) {
            double percentage = (totalCategoriesFound.get() * 100.0) / totalCategories;
            progress.put("percentage", Math.round(percentage * 10) / 10.0);
        } else {
            progress.put("percentage", 0);
        }
        
        return progress;
    }

    /**
     * Get all categories from database
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getAllCategories() {
        Map<String, Object> response = new HashMap<>();
        List<Category> categories = categoryRepository.findAll();
        
        List<Map<String, Object>> categoryList = new ArrayList<>();
        for (Category category : categories) {
            Map<String, Object> catMap = new HashMap<>();
            catMap.put("id", category.getId());
            catMap.put("name", category.getCategoryName());
            catMap.put("isActive", category.getIsActive());
            categoryList.add(catMap);
        }

        response.put("success", true);
        response.put("total", categories.size());
        response.put("categories", categoryList);
        return response;
    }

    /**
     * Get categories count
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getCategoriesCount() {
        Map<String, Object> response = new HashMap<>();
        long count = categoryRepository.countCategories();
        long activeCount = categoryRepository.findAllActiveCategories().size();

        response.put("success", true);
        response.put("totalCategories", count);
        response.put("activeCategories", activeCount);
        return response;
    }

    /**
     * Search categories by keyword
     */
    @Transactional(readOnly = true)
    public Map<String, Object> searchCategories(String keyword) {
        Map<String, Object> response = new HashMap<>();
        
        List<Category> categories = categoryRepository.searchCategories(keyword);
        
        List<Map<String, Object>> categoryList = new ArrayList<>();
        for (Category category : categories) {
            Map<String, Object> catMap = new HashMap<>();
            catMap.put("id", category.getId());
            catMap.put("name", category.getCategoryName());
            catMap.put("isActive", category.getIsActive());
            categoryList.add(catMap);
        }

        response.put("success", true);
        response.put("total", categoryList.size());
        response.put("categories", categoryList);
        return response;
    }

    // WebDriver methods
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

        options.setExperimentalOption("excludeSwitches", new String[] { "enable-automation" });
        options.setExperimentalOption("useAutomationExtension", false);

        WebDriver driver = new ChromeDriver(options);

        ((JavascriptExecutor) driver)
                .executeScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");

        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(PAGE_LOAD_TIMEOUT));
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));

        return driver;
    }

    private void navigateWithRetry(WebDriver driver, String url) {
        int retries = 0;
        while (retries < 3) {
            try {
                driver.get(url);
                return;
            } catch (TimeoutException e) {
                retries++;
                if (retries >= 3) throw e;
                sleepRandom(2000, 3000);
            }
        }
    }

    private void sleepRandom(int minMs, int maxMs) {
        try {
            int sleepTime = minMs + random.nextInt(maxMs - minMs);
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}