// File: AdvGeMScrapingService.java
package in.BidPilots.service.AdvService;

import in.BidPilots.entity.AdvEntity.AdvMinistry;
import in.BidPilots.entity.AdvEntity.AdvState;
import in.BidPilots.entity.AdvEntity.AdvOrganization;
import in.BidPilots.entity.AdvEntity.AdvDepartment;
import in.BidPilots.repository.AdvRepository.AdvMinistryRepository;
import in.BidPilots.repository.AdvRepository.AdvStateRepository;
import in.BidPilots.repository.AdvRepository.AdvOrganizationRepository;
import in.BidPilots.repository.AdvRepository.AdvDepartmentRepository;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdvGeMScrapingService {

    private final AdvMinistryRepository advMinistryRepository;
    private final AdvStateRepository advStateRepository;
    private final AdvOrganizationRepository advOrganizationRepository;
    private final AdvDepartmentRepository advDepartmentRepository;

    @Value("${gem.portal.base.url:https://bidplus.gem.gov.in}")
    private String baseUrl;

    // OPTIMIZED FOR SPEED - ONE TIME PROCESS
    private static final int PAGE_LOAD_TIMEOUT = 30;
    private static final int MAX_RETRIES = 2;
    
    // Minimal delays for speed
    private static final int MIN_DELAY_MS = 100;      // 100ms between actions
    private static final int MAX_DELAY_MS = 300;      // 300ms max delay
    private static final int BATCH_SAVE_SIZE = 500;   // Save in batches of 500

    // Rotating user agents (minimal)
    private static final String[] USER_AGENTS = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    };

    // Viewport sizes (single for speed)
    private static final String[] VIEWPORT_SIZES = { "1920,1080" };

    private final Random random = new Random();

    // Progress tracking
    private final AtomicInteger ministriesScraped = new AtomicInteger(0);
    private final AtomicInteger statesScraped = new AtomicInteger(0);
    private final AtomicInteger organizationsScraped = new AtomicInteger(0);
    private final AtomicInteger departmentsScraped = new AtomicInteger(0);
    private volatile boolean isScrapingActive = false;
    private volatile String currentStatus = "Idle";

    // Cache for existing data to prevent duplicate checks
    private Set<String> existingMinistryNames = ConcurrentHashMap.newKeySet();
    private Set<String> existingStateNames = ConcurrentHashMap.newKeySet();
    private Set<String> existingOrganizationKeys = ConcurrentHashMap.newKeySet();
    private Set<String> existingDepartmentKeys = ConcurrentHashMap.newKeySet();

    /**
     * OPTIMIZED: Scrape ALL metadata from GeM portal advance search
     * - One-time process with duplicate prevention
     * - Batch saving for speed
     * - Minimal delays
     */
    @Transactional
    public Map<String, Object> scrapeAllAdvanceSearchData() {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();

        if (isScrapingActive) {
            result.put("success", false);
            result.put("message", "Scraping already in progress");
            return result;
        }

        isScrapingActive = true;
        currentStatus = "Starting advance search data extraction...";
        
        // Reset counters
        ministriesScraped.set(0);
        statesScraped.set(0);
        organizationsScraped.set(0);
        departmentsScraped.set(0);

        // Load existing data into cache
        loadExistingDataIntoCache();

        log.info("=".repeat(100));
        log.info("🚀 GEM ADVANCE SEARCH SCRAPING SERVICE - OPTIMIZED FOR SPEED");
        log.info("=".repeat(100));
        log.info("   Targets: Ministries, States, Organizations, Departments");
        log.info("   Mode: ONE-TIME PROCESS with duplicate prevention");
        log.info("   Existing in DB: Ministries={}, States={}, Orgs={}, Depts={}", 
                existingMinistryNames.size(), existingStateNames.size(),
                existingOrganizationKeys.size(), existingDepartmentKeys.size());
        log.info("   Batch Save Size: {}", BATCH_SAVE_SIZE);
        log.info("=".repeat(100));

        WebDriver driver = null;

        try {
            driver = createStealthWebDriver();

            // Navigate to advance search page
            log.info("Navigating to advance search page...");
            navigateWithRetry(driver, baseUrl + "/advance-search");
            sleepRandom(1000, 2000); // Minimal delay

            // Click on "Search by Ministry/Organization" tab
            clickElementNaturally(driver, By.cssSelector("a[href='#tab1']"));
            sleepRandom(500, 1000);

            // STEP 1: Scrape Ministries (FAST)
            log.info("📋 STEP 1: Scraping Ministries...");
            List<String> ministries = scrapeMinistries(driver);
            
            List<AdvMinistry> ministryBatch = new ArrayList<>();
            int ministryNewCount = 0;
            
            for (String ministryName : ministries) {
                if (!existingMinistryNames.contains(ministryName)) {
                    AdvMinistry ministry = new AdvMinistry();
                    ministry.setMinistryName(ministryName);
                    ministryBatch.add(ministry);
                    existingMinistryNames.add(ministryName); // Add to cache
                    ministryNewCount++;
                    
                    if (ministryBatch.size() >= BATCH_SAVE_SIZE) {
                        advMinistryRepository.saveAll(ministryBatch);
                        ministryBatch.clear();
                    }
                }
            }
            
            if (!ministryBatch.isEmpty()) {
                advMinistryRepository.saveAll(ministryBatch);
            }
            
            ministriesScraped.set(ministryNewCount);
            log.info("✅ Scraped {} ministries (New: {}, Total: {})", 
                    ministries.size(), ministryNewCount, existingMinistryNames.size());

            // STEP 2: Scrape States (Buyer States)
            log.info("📋 STEP 2: Scraping Buyer States...");
            List<String> states = scrapeBuyerStates(driver);
            
            List<AdvState> stateBatch = new ArrayList<>();
            int stateNewCount = 0;
            
            for (String stateName : states) {
                if (!existingStateNames.contains(stateName)) {
                    AdvState state = new AdvState();
                    state.setStateName(stateName);
                    stateBatch.add(state);
                    existingStateNames.add(stateName); // Add to cache
                    stateNewCount++;
                    
                    if (stateBatch.size() >= BATCH_SAVE_SIZE) {
                        advStateRepository.saveAll(stateBatch);
                        stateBatch.clear();
                    }
                }
            }
            
            if (!stateBatch.isEmpty()) {
                advStateRepository.saveAll(stateBatch);
            }
            
            statesScraped.set(stateNewCount);
            log.info("✅ Scraped {} states (New: {}, Total: {})", 
                    states.size(), stateNewCount, existingStateNames.size());

            // STEP 3: Get all ministries from DB for processing
            List<AdvMinistry> savedMinistries = advMinistryRepository.findAll();
            log.info("📋 STEP 3: Processing Organizations for {} Ministries...", savedMinistries.size());
            
            List<AdvOrganization> orgBatch = new ArrayList<>();
            List<AdvDepartment> deptBatch = new ArrayList<>();
            int orgNewCount = 0;
            int deptNewCount = 0;
            
            // Process each ministry
            for (AdvMinistry ministry : savedMinistries) {
                log.debug("   Processing organizations for Ministry: {}", ministry.getMinistryName());
                
                // Select ministry
                selectDropdownNaturally(driver, By.id("ministry"), ministry.getMinistryName());
                sleepRandom(500, 1000); // Minimal delay
                
                // Scrape organizations for this ministry
                List<String> orgsForMinistry = scrapeOrganizations(driver);
                
                for (String orgName : orgsForMinistry) {
                    String orgKey = ministry.getId() + ":" + orgName;
                    
                    if (!existingOrganizationKeys.contains(orgKey)) {
                        AdvOrganization org = new AdvOrganization();
                        org.setOrganizationName(orgName);
                        org.setAdvMinistry(ministry);
                        orgBatch.add(org);
                        existingOrganizationKeys.add(orgKey);
                        orgNewCount++;
                        
                        // Get departments for this organization
                        if (orgName != null && !orgName.isEmpty()) {
                            // Select organization
                            selectDropdownNaturally(driver, By.id("organization"), orgName);
                            sleepRandom(500, 1000);
                            
                            // Scrape departments
                            List<String> depts = scrapeDepartments(driver);
                            
                            for (String deptName : depts) {
                                String deptKey = ministry.getId() + ":" + orgName + ":" + deptName;
                                
                                if (!existingDepartmentKeys.contains(deptKey)) {
                                    AdvDepartment dept = new AdvDepartment();
                                    dept.setDepartmentName(deptName);
                                    dept.setAdvMinistry(ministry);
                                    dept.setAdvOrganization(org); // Will set ID after org is saved
                                    deptBatch.add(dept);
                                    existingDepartmentKeys.add(deptKey);
                                    deptNewCount++;
                                }
                            }
                            
                            // Reset organization selection for next iteration
                            resetOrganizationSelection(driver);
                            sleepRandom(300, 500);
                        }
                        
                        // Save batches when they reach size
                        if (orgBatch.size() >= BATCH_SAVE_SIZE) {
                            List<AdvOrganization> savedOrgs = advOrganizationRepository.saveAll(orgBatch);
                            linkDepartmentsToOrganizations(savedOrgs, deptBatch);
                            if (!deptBatch.isEmpty()) {
                                advDepartmentRepository.saveAll(deptBatch);
                                deptBatch.clear();
                            }
                            orgBatch.clear();
                        }
                    }
                }
                
                // Reset ministry selection
                resetMinistrySelection(driver);
                sleepRandom(500, 1000);
            }
            
            // Save any remaining organizations
            if (!orgBatch.isEmpty()) {
                List<AdvOrganization> savedOrgs = advOrganizationRepository.saveAll(orgBatch);
                linkDepartmentsToOrganizations(savedOrgs, deptBatch);
            }
            
            // Save any remaining departments
            if (!deptBatch.isEmpty()) {
                advDepartmentRepository.saveAll(deptBatch);
            }
            
            organizationsScraped.set(orgNewCount);
            departmentsScraped.set(deptNewCount);

            // STEP 4: Process States for Organizations (if needed)
            List<AdvState> savedStates = advStateRepository.findAll();
            if (!savedStates.isEmpty()) {
                log.info("📋 STEP 4: Processing Organizations for {} States...", savedStates.size());
                
                orgBatch.clear();
                deptBatch.clear();
                int stateOrgNewCount = 0;
                int stateDeptNewCount = 0;
                
                for (AdvState state : savedStates) {
                    log.debug("   Processing organizations for State: {}", state.getStateName());
                    
                    // Select state
                    selectDropdownNaturally(driver, By.id("buyer_state"), state.getStateName());
                    sleepRandom(500, 1000);
                    
                    // Scrape organizations for this state
                    List<String> orgsForState = scrapeOrganizations(driver);
                    
                    for (String orgName : orgsForState) {
                        String orgKey = "STATE_" + state.getId() + ":" + orgName;
                        
                        if (!existingOrganizationKeys.contains(orgKey)) {
                            AdvOrganization org = new AdvOrganization();
                            org.setOrganizationName(orgName);
                            org.setAdvState(state);
                            orgBatch.add(org);
                            existingOrganizationKeys.add(orgKey);
                            stateOrgNewCount++;
                            
                            // Get departments for this state+organization
                            if (orgName != null && !orgName.isEmpty()) {
                                // Select organization
                                selectDropdownNaturally(driver, By.id("organization"), orgName);
                                sleepRandom(500, 1000);
                                
                                // Scrape departments
                                List<String> depts = scrapeDepartments(driver);
                                
                                for (String deptName : depts) {
                                    String deptKey = "STATE_" + state.getId() + ":" + orgName + ":" + deptName;
                                    
                                    if (!existingDepartmentKeys.contains(deptKey)) {
                                        AdvDepartment dept = new AdvDepartment();
                                        dept.setDepartmentName(deptName);
                                        dept.setAdvState(state);
                                        dept.setAdvOrganization(org);
                                        deptBatch.add(dept);
                                        existingDepartmentKeys.add(deptKey);
                                        stateDeptNewCount++;
                                    }
                                }
                                
                                // Reset organization selection
                                resetOrganizationSelection(driver);
                                sleepRandom(300, 500);
                            }
                            
                            // Save batches
                            if (orgBatch.size() >= BATCH_SAVE_SIZE) {
                                List<AdvOrganization> savedOrgs = advOrganizationRepository.saveAll(orgBatch);
                                linkDepartmentsToOrganizations(savedOrgs, deptBatch);
                                if (!deptBatch.isEmpty()) {
                                    advDepartmentRepository.saveAll(deptBatch);
                                    deptBatch.clear();
                                }
                                orgBatch.clear();
                            }
                        }
                    }
                    
                    // Reset state selection
                    resetStateSelection(driver);
                    sleepRandom(500, 1000);
                }
                
                // Save remaining
                if (!orgBatch.isEmpty()) {
                    List<AdvOrganization> savedOrgs = advOrganizationRepository.saveAll(orgBatch);
                    linkDepartmentsToOrganizations(savedOrgs, deptBatch);
                }
                if (!deptBatch.isEmpty()) {
                    advDepartmentRepository.saveAll(deptBatch);
                }
                
                organizationsScraped.addAndGet(stateOrgNewCount);
                departmentsScraped.addAndGet(stateDeptNewCount);
                
                log.info("   States processing completed - New Orgs: {}, New Depts: {}", 
                        stateOrgNewCount, stateDeptNewCount);
            }

            long timeTaken = (System.currentTimeMillis() - startTime) / 1000;
            
            log.info("=".repeat(100));
            log.info("✅ ADVANCE SEARCH SCRAPING COMPLETED");
            log.info("=".repeat(100));
            log.info("   Ministries Scraped (New): {}", ministriesScraped.get());
            log.info("   States Scraped (New): {}", statesScraped.get());
            log.info("   Organizations Scraped (New): {}", organizationsScraped.get());
            log.info("   Departments Scraped (New): {}", departmentsScraped.get());
            log.info("   Final Totals: Ministries={}, States={}, Orgs={}, Depts={}",
                    advMinistryRepository.count(), advStateRepository.count(),
                    advOrganizationRepository.count(), advDepartmentRepository.count());
            log.info("   Time Taken: {} seconds ({} minutes)", timeTaken, timeTaken / 60);
            log.info("=".repeat(100));

            result.put("success", true);
            result.put("message", "Advance search data scraped successfully");
            result.put("ministriesScraped", ministriesScraped.get());
            result.put("statesScraped", statesScraped.get());
            result.put("organizationsScraped", organizationsScraped.get());
            result.put("departmentsScraped", departmentsScraped.get());
            result.put("timeTakenSeconds", timeTaken);
            result.put("totalMinistries", advMinistryRepository.count());
            result.put("totalStates", advStateRepository.count());
            result.put("totalOrganizations", advOrganizationRepository.count());
            result.put("totalDepartments", advDepartmentRepository.count());
            
            currentStatus = "Completed";

            return result;

        } catch (Exception e) {
            log.error("Error in advance search scraping: {}", e.getMessage(), e);
            currentStatus = "Failed: " + e.getMessage();
            result.put("success", false);
            result.put("message", "Scraping failed: " + e.getMessage());
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
     * Link departments to their saved organizations
     */
    private void linkDepartmentsToOrganizations(List<AdvOrganization> savedOrgs, List<AdvDepartment> deptBatch) {
        // Create a map of org names to saved orgs for department linking
        Map<String, AdvOrganization> orgMap = savedOrgs.stream()
                .collect(Collectors.toMap(
                    org -> {
                        if (org.getAdvMinistry() != null) {
                            return org.getAdvMinistry().getId() + ":" + org.getOrganizationName();
                        } else if (org.getAdvState() != null) {
                            return "STATE_" + org.getAdvState().getId() + ":" + org.getOrganizationName();
                        }
                        return org.getOrganizationName();
                    },
                    org -> org,
                    (existing, replacement) -> existing
                ));
        
        // Link departments to saved organizations
        for (AdvDepartment dept : deptBatch) {
            String orgKey;
            if (dept.getAdvMinistry() != null) {
                orgKey = dept.getAdvMinistry().getId() + ":" + dept.getAdvOrganization().getOrganizationName();
            } else if (dept.getAdvState() != null) {
                orgKey = "STATE_" + dept.getAdvState().getId() + ":" + dept.getAdvOrganization().getOrganizationName();
            } else {
                continue;
            }
            
            AdvOrganization savedOrg = orgMap.get(orgKey);
            if (savedOrg != null) {
                dept.setAdvOrganization(savedOrg);
            }
        }
    }

    /**
     * Load existing data into cache for duplicate prevention
     */
    private void loadExistingDataIntoCache() {
        log.info("Loading existing data into cache for duplicate prevention...");
        
        // Load ministries
        List<AdvMinistry> ministries = advMinistryRepository.findAll();
        ministries.forEach(m -> existingMinistryNames.add(m.getMinistryName()));
        
        // Load states
        List<AdvState> states = advStateRepository.findAll();
        states.forEach(s -> existingStateNames.add(s.getStateName()));
        
        // Load organizations with composite keys
        List<AdvOrganization> orgs = advOrganizationRepository.findAll();
        orgs.forEach(o -> {
            String key = "";
            if (o.getAdvMinistry() != null) {
                key = o.getAdvMinistry().getId() + ":" + o.getOrganizationName();
            } else if (o.getAdvState() != null) {
                key = "STATE_" + o.getAdvState().getId() + ":" + o.getOrganizationName();
            }
            if (!key.isEmpty()) {
                existingOrganizationKeys.add(key);
            }
        });
        
        // Load departments with composite keys
        List<AdvDepartment> depts = advDepartmentRepository.findAll();
        depts.forEach(d -> {
            String key = "";
            if (d.getAdvOrganization() != null) {
                if (d.getAdvMinistry() != null) {
                    key = d.getAdvMinistry().getId() + ":" + 
                          d.getAdvOrganization().getOrganizationName() + ":" + 
                          d.getDepartmentName();
                } else if (d.getAdvState() != null) {
                    key = "STATE_" + d.getAdvState().getId() + ":" + 
                          d.getAdvOrganization().getOrganizationName() + ":" + 
                          d.getDepartmentName();
                }
            }
            if (!key.isEmpty()) {
                existingDepartmentKeys.add(key);
            }
        });
        
        log.info("Cache loaded - Ministries: {}, States: {}, Orgs: {}, Depts: {}",
                existingMinistryNames.size(), existingStateNames.size(),
                existingOrganizationKeys.size(), existingDepartmentKeys.size());
    }

    /**
     * Scrape ministries from dropdown
     */
    private List<String> scrapeMinistries(WebDriver driver) {
        List<String> ministries = new ArrayList<>();
        try {
            WebElement ministryDropdown = driver.findElement(By.id("ministry"));
            Select select = new Select(ministryDropdown);
            List<WebElement> options = select.getOptions();
            
            for (int i = 1; i < options.size(); i++) {
                String text = options.get(i).getText().trim();
                if (!text.isEmpty() && !text.equals("---Select---")) {
                    ministries.add(text);
                }
            }
        } catch (Exception e) {
            log.error("Error scraping ministries: {}", e.getMessage());
        }
        return ministries;
    }

    /**
     * Scrape buyer states from dropdown
     */
    private List<String> scrapeBuyerStates(WebDriver driver) {
        List<String> states = new ArrayList<>();
        try {
            WebElement stateDropdown = driver.findElement(By.id("buyer_state"));
            Select select = new Select(stateDropdown);
            List<WebElement> options = select.getOptions();
            
            for (int i = 1; i < options.size(); i++) {
                String text = options.get(i).getText().trim();
                if (!text.isEmpty() && !text.equals("---Select---")) {
                    states.add(text);
                }
            }
        } catch (Exception e) {
            log.error("Error scraping buyer states: {}", e.getMessage());
        }
        return states;
    }

    /**
     * OPTIMIZED: Scrape organizations from dropdown with faster wait
     */
    private List<String> scrapeOrganizations(WebDriver driver) {
        List<String> organizations = new ArrayList<>();
        try {
            // Minimal wait - just enough for dropdown to populate
            sleepRandom(300, 500);
            
            WebElement orgDropdown = driver.findElement(By.id("organization"));
            Select select = new Select(orgDropdown);
            List<WebElement> options = select.getOptions();
            
            for (int i = 1; i < options.size(); i++) {
                String text = options.get(i).getText().trim();
                if (!text.isEmpty() && !text.equals("---Select---") && !text.equals("No data found")) {
                    organizations.add(text);
                }
            }
        } catch (Exception e) {
            log.debug("No organizations found or dropdown not available");
        }
        return organizations;
    }

    /**
     * OPTIMIZED: Scrape departments from dropdown with faster wait
     */
    private List<String> scrapeDepartments(WebDriver driver) {
        List<String> departments = new ArrayList<>();
        try {
            // Minimal wait
            sleepRandom(300, 500);
            
            WebElement deptDropdown = driver.findElement(By.id("department"));
            Select select = new Select(deptDropdown);
            List<WebElement> options = select.getOptions();
            
            for (int i = 1; i < options.size(); i++) {
                String text = options.get(i).getText().trim();
                if (!text.isEmpty() && !text.equals("---Select---") && !text.equals("No data found")) {
                    departments.add(text);
                }
            }
        } catch (Exception e) {
            log.debug("No departments found or dropdown not available");
        }
        return departments;
    }

    /**
     * Reset ministry selection to default
     */
    private void resetMinistrySelection(WebDriver driver) {
        try {
            WebElement ministryDropdown = driver.findElement(By.id("ministry"));
            Select ministrySelect = new Select(ministryDropdown);
            ministrySelect.selectByIndex(0); // Select the first option (---Select---)
            sleepRandom(300, 500);
        } catch (Exception e) {
            log.debug("Error resetting ministry selection: {}", e.getMessage());
        }
    }

    /**
     * Reset state selection to default
     */
    private void resetStateSelection(WebDriver driver) {
        try {
            WebElement stateDropdown = driver.findElement(By.id("buyer_state"));
            Select stateSelect = new Select(stateDropdown);
            stateSelect.selectByIndex(0); // Select the first option (---Select---)
            sleepRandom(300, 500);
        } catch (Exception e) {
            log.debug("Error resetting state selection: {}", e.getMessage());
        }
    }

    /**
     * Reset organization selection to default
     */
    private void resetOrganizationSelection(WebDriver driver) {
        try {
            WebElement orgDropdown = driver.findElement(By.id("organization"));
            Select orgSelect = new Select(orgDropdown);
            orgSelect.selectByIndex(0); // Select the first option (---Select---)
            sleepRandom(300, 500);
        } catch (Exception e) {
            log.debug("Error resetting organization selection: {}", e.getMessage());
        }
    }

    /**
     * Reset both ministry and state selection
     */
    private void resetMinistryStateSelection(WebDriver driver) {
        resetMinistrySelection(driver);
        resetStateSelection(driver);
    }

    /**
     * FIXED: Select dropdown with proper text handling
     */
    private void selectDropdownNaturally(WebDriver driver, By by, String value) {
        try {
            WebElement dropdown = waitForElement(driver, by, 5);
            ((JavascriptExecutor) driver)
                    .executeScript("arguments[0].scrollIntoView({behavior: 'auto', block: 'center'});", dropdown);
            sleepRandom(100, 200);
            Select select = new Select(dropdown);
            
            // Try both formats
            try {
                select.selectByVisibleText(value);
            } catch (Exception e) {
                // If value is "--Select--", try "---Select---"
                if (value.equals("--Select--")) {
                    select.selectByVisibleText("---Select---");
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            log.debug("Could not select value {}: {}", value, e.getMessage());
        }
    }

    /**
     * Get scraping progress
     */
    public Map<String, Object> getProgress() {
        Map<String, Object> progress = new HashMap<>();
        progress.put("isActive", isScrapingActive);
        progress.put("status", currentStatus);
        progress.put("ministriesScraped", ministriesScraped.get());
        progress.put("statesScraped", statesScraped.get());
        progress.put("organizationsScraped", organizationsScraped.get());
        progress.put("departmentsScraped", departmentsScraped.get());
        
        long totalMinistries = advMinistryRepository.count();
        long totalStates = advStateRepository.count();
        long totalOrgs = advOrganizationRepository.count();
        long totalDepts = advDepartmentRepository.count();
        
        progress.put("totalMinistries", totalMinistries);
        progress.put("totalStates", totalStates);
        progress.put("totalOrganizations", totalOrgs);
        progress.put("totalDepartments", totalDepts);
        
        return progress;
    }

    /**
     * Get statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        
        stats.put("success", true);
        stats.put("totalMinistries", advMinistryRepository.count());
        stats.put("totalStates", advStateRepository.count());
        stats.put("totalOrganizations", advOrganizationRepository.count());
        stats.put("totalDepartments", advDepartmentRepository.count());
        
        return stats;
    }

    /**
     * Clear all data
     */
    @Transactional
    public Map<String, Object> clearAllData() {
        Map<String, Object> result = new HashMap<>();
        
        long deptCount = advDepartmentRepository.count();
        long orgCount = advOrganizationRepository.count();
        long ministryCount = advMinistryRepository.count();
        long stateCount = advStateRepository.count();
        
        advDepartmentRepository.deleteAll();
        advOrganizationRepository.deleteAll();
        advMinistryRepository.deleteAll();
        advStateRepository.deleteAll();
        
        // Clear caches
        existingMinistryNames.clear();
        existingStateNames.clear();
        existingOrganizationKeys.clear();
        existingDepartmentKeys.clear();
        
        result.put("success", true);
        result.put("message", "All advance search data cleared");
        result.put("deletedDepartments", deptCount);
        result.put("deletedOrganizations", orgCount);
        result.put("deletedMinistries", ministryCount);
        result.put("deletedStates", stateCount);
        
        return result;
    }

    // WebDriver methods (optimized for speed)
    private WebDriver createStealthWebDriver() {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");

        // Single viewport for speed
        options.addArguments("--window-size=1920,1080");

        // Single user agent
        options.addArguments("--user-agent=" + USER_AGENTS[0]);

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
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(2)); // Reduced implicit wait

        return driver;
    }

    private void navigateWithRetry(WebDriver driver, String url) {
        int retries = 0;
        while (retries < 2) {
            try {
                driver.get(url);
                return;
            } catch (TimeoutException e) {
                retries++;
                if (retries >= 2) throw e;
                sleepRandom(2000, 3000);
            }
        }
    }

    private void clickElementNaturally(WebDriver driver, By by) {
        try {
            WebElement element = waitForElement(driver, by, 5);
            ((JavascriptExecutor) driver)
                    .executeScript("arguments[0].scrollIntoView({behavior: 'auto', block: 'center'});", element);
            sleepRandom(100, 300);
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
        } catch (Exception e) {
            driver.findElement(by).click();
        }
    }

    private WebElement waitForElement(WebDriver driver, By by, int maxSeconds) {
        int attempts = 0;
        int maxAttempts = maxSeconds * 4; // 250ms per attempt

        while (attempts < maxAttempts) {
            try {
                return driver.findElement(by);
            } catch (NoSuchElementException e) {
                sleepRandom(200, 300);
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
}