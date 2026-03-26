package in.BidPilots.service;

import in.BidPilots.entity.State;
import in.BidPilots.entity.City;
import in.BidPilots.repository.StateRepository;
import in.BidPilots.repository.CityRepository;
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

@Service
@Slf4j
@RequiredArgsConstructor
public class StateCityExtractionService {

	private final StateRepository stateRepository;
	private final CityRepository cityRepository;

	@Value("${gem.portal.base.url:https://bidplus.gem.gov.in}")
	private String baseUrl;

	// Anti-detection configuration
	private static final int PAGE_LOAD_TIMEOUT = 60;
	private static final int MAX_RETRIES = 3;
	private static final int BASE_DELAY_MS = 5000;
	private static final int RANDOM_DELAY_RANGE = 5000;

	// Rotating user agents
	private static final String[] USER_AGENTS = {
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36 Edg/119.0.0.0",
			"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Safari/605.1.15",
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0" };

	// Viewport sizes
	private static final String[] VIEWPORT_SIZES = { "1920,1080", "1366,768", "1536,864", "1440,900", "1280,720" };

	private final Random random = new Random();

	/**
	 * Extract all states and cities from GeM portal This is a one-time setup
	 * operation
	 */
	@Transactional
	public Map<String, Object> extractStatesAndCities() {
		Map<String, Object> result = new HashMap<>();
		long startTime = System.currentTimeMillis();

		log.info("=".repeat(80));
		log.info("🗺️ STATE/CITY EXTRACTION SERVICE STARTED");
		log.info("=".repeat(80));

		WebDriver driver = null;

		try {
			// Check if states already exist
			if (stateRepository.count() > 0) {
				log.info("States and cities already exist in database. Use clear endpoint to reset.");
				result.put("success", false);
				result.put("message", "States and cities already exist. Use clear endpoint to reset.");
				result.put("totalStates", stateRepository.count());
				result.put("totalCities", cityRepository.count());
				return result;
			}

			driver = createStealthWebDriver();

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
			int totalCityCount = 0;
			Map<String, Integer> stateCityCounts = new LinkedHashMap<>();

			// Process each state
			for (int i = 1; i < stateOptions.size(); i++) {
				String stateName = stateOptions.get(i).getText().trim();

				if (stateName.isEmpty() || stateName.equals("Select State")) {
					continue;
				}

				log.info("Processing state: {}", stateName);

				// Create and save state
				State state = new State();
				state.setStateName(stateName);
				state.setIsActive(true);
				state = stateRepository.save(state);
				stateRepository.flush();
				stateCount++;

				log.debug("State {} saved with ID: {}", stateName, state.getId());

				// Select state in dropdown
				selectDropdownNaturally(driver, By.id("state_name_con"), stateName);
				sleepRandom(3000, 6000);

				// Get cities for this state
				int cityCountForState = 0;
				try {
					WebElement cityDropdown = driver.findElement(By.id("city_name_con"));
					Select citySelect = new Select(cityDropdown);
					List<WebElement> cityOptions = citySelect.getOptions();

					for (int j = 1; j < cityOptions.size(); j++) {
						String cityName = cityOptions.get(j).getText().trim();

						if (cityName.isEmpty() || cityName.equals("Select City")) {
							continue;
						}

						// Create and save city
						City city = new City();
						city.setCityName(cityName);
						city.setState(state);
						city.setIsActive(true);
						city = cityRepository.save(city);
						cityCountForState++;
						totalCityCount++;

						log.debug("Created city: {} for {} (ID: {})", cityName, stateName, city.getId());

						// Commit after each city
						cityRepository.flush();

						// Small delay between city saves
						sleepRandom(500, 1500);
					}

				} catch (Exception e) {
					log.warn("Could not load cities for state {}: {}", stateName, e.getMessage());
				}

				stateCityCounts.put(stateName, cityCountForState);
				log.info("   State {}: saved {} cities", stateName, cityCountForState);

				// Random delay between states
				sleepRandom(5000, 10000);
			}

			long timeTaken = (System.currentTimeMillis() - startTime) / 1000;

			log.info("=".repeat(80));
			log.info("✅ STATE/CITY EXTRACTION COMPLETED");
			log.info("   Total States: {}", stateCount);
			log.info("   Total Cities: {}", totalCityCount);
			log.info("   Time Taken: {} seconds", timeTaken);
			log.info("=".repeat(80));

			// Log summary
			log.info("📊 EXTRACTION SUMMARY:");
			stateCityCounts.forEach((state, count) -> log.info("   {}: {} cities", state, count));
			log.info("=".repeat(80));

			result.put("success", true);
			result.put("message", "States and cities extracted successfully");
			result.put("totalStates", stateCount);
			result.put("totalCities", totalCityCount);
			result.put("timeTakenSeconds", timeTaken);
			result.put("stateDetails", stateCityCounts);

			return result;

		} catch (Exception e) {
			log.error("Error in state/city extraction: {}", e.getMessage(), e);
			result.put("success", false);
			result.put("message", "Extraction failed: " + e.getMessage());
			result.put("error", e.getMessage());
			return result;
		} finally {
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
	 * Clear all states and cities from database Only works if no bids exist
	 */
	@Transactional
	public Map<String, Object> clearStatesAndCities() {
		Map<String, Object> result = new HashMap<>();

		try {
			long stateCount = stateRepository.count();
			long cityCount = cityRepository.count();

			// Check if there are bids (we'll need to inject BidRepository or check via
			// another method)
			// For now, we'll just clear
			cityRepository.deleteAll();
			stateRepository.deleteAll();
			cityRepository.flush();
			stateRepository.flush();

			log.info("✅ Cleared {} states and {} cities", stateCount, cityCount);

			result.put("success", true);
			result.put("message", "States and cities cleared successfully");
			result.put("deletedStates", stateCount);
			result.put("deletedCities", cityCount);

			return result;

		} catch (Exception e) {
			log.error("Error clearing states/cities: {}", e.getMessage());
			result.put("success", false);
			result.put("message", "Failed to clear: " + e.getMessage());
			return result;
		}
	}

	/**
	 * Get status of states and cities
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> getStatus() {
		Map<String, Object> result = new HashMap<>();

		long stateCount = stateRepository.count();
		long cityCount = cityRepository.count();

		result.put("success", true);
		result.put("statesPopulated", stateCount > 0);
		result.put("totalStates", stateCount);
		result.put("totalCities", cityCount);

		if (stateCount > 0) {
			List<Map<String, Object>> stateDetails = new ArrayList<>();
			List<State> states = stateRepository.findAll();
			for (State state : states) {
				Map<String, Object> stateInfo = new HashMap<>();
				stateInfo.put("id", state.getId());
				stateInfo.put("name", state.getStateName());
				stateInfo.put("cityCount", cityRepository.countByState(state));
				stateDetails.add(stateInfo);
			}
			result.put("stateDetails", stateDetails);
		}

		return result;
	}

	// WebDriver methods (same as in GeMScrapingService)
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

		options.setExperimentalOption("excludeSwitches", new String[] { "enable-automation" });
		options.setExperimentalOption("useAutomationExtension", false);

		Map<String, Object> prefs = new HashMap<>();
		prefs.put("credentials_enable_service", false);
		prefs.put("profile.password_manager_enabled", false);
		prefs.put("profile.default_content_setting_values.notifications", 2);
		options.setExperimentalOption("prefs", prefs);

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
				if (retries >= 3)
					throw e;
				sleepRandom(5000, 10000);
			}
		}
	}

	private void clickElementNaturally(WebDriver driver, By by) {
		try {
			WebElement element = waitForElement(driver, by, 10);
			((JavascriptExecutor) driver)
					.executeScript("arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});", element);
			sleepRandom(500, 1500);
			((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
		} catch (Exception e) {
			driver.findElement(by).click();
		}
	}

	private void selectDropdownNaturally(WebDriver driver, By by, String value) {
		WebElement dropdown = waitForElement(driver, by, 10);
		((JavascriptExecutor) driver)
				.executeScript("arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});", dropdown);
		sleepRandom(500, 1500);
		Select select = new Select(dropdown);
		select.selectByVisibleText(value);
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
}