// File: AdvScrapingController.java
package in.BidPilots.controller.AdvController;

import in.BidPilots.service.AdvService.AdvGeMScrapingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/advance-search/scraping")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class AdvScrapingController {

	private final AdvGeMScrapingService advGeMScrapingService;

	/**
	 * Start scraping all advance search data from GeM portal This will extract
	 * Ministries, States, Organizations, and Departments
	 */
	@PostMapping("/start")
	public ResponseEntity<Map<String, Object>> startScraping() {
		log.info("🚀 Starting advance search data scraping");

		Map<String, Object> result = advGeMScrapingService.scrapeAllAdvanceSearchData();

		if (result.containsKey("success") && (Boolean) result.get("success")) {
			return ResponseEntity.ok(result);
		} else {
			return ResponseEntity.badRequest().body(result);
		}
	}

	/**
	 * Get current scraping progress
	 */
	@GetMapping("/progress")
	public ResponseEntity<Map<String, Object>> getScrapeProgress() {
		Map<String, Object> progress = advGeMScrapingService.getProgress();
		progress.put("success", true);
		return ResponseEntity.ok(progress);
	}

	/**
	 * Get statistics of scraped data
	 */
	@GetMapping("/stats")
	public ResponseEntity<Map<String, Object>> getScrapingStats() {
		Map<String, Object> stats = advGeMScrapingService.getStats();
		return ResponseEntity.ok(stats);
	}

	/**
	 * Clear all scraped advance search data DANGER ZONE: This will delete all
	 * ministries, states, organizations, and departments
	 */
	@DeleteMapping("/clear")
	public ResponseEntity<Map<String, Object>> clearAllScrapedData() {
		log.warn("⚠️ Clearing all advance search data");
		Map<String, Object> result = advGeMScrapingService.clearAllData();

		if (result.containsKey("success") && (Boolean) result.get("success")) {
			return ResponseEntity.ok(result);
		} else {
			return ResponseEntity.badRequest().body(result);
		}
	}

	/**
	 * Manually trigger specific scraping parts (for testing)
	 */
	@PostMapping("/test/scrape-ministries")
	public ResponseEntity<Map<String, Object>> testScrapeMinistries() {
		log.info("🧪 Test scraping only ministries");
		// This would need a new method in service
		Map<String, Object> response = new HashMap<>();
		response.put("success", true);
		response.put("message", "Test endpoint - implement specific scraping logic");
		return ResponseEntity.ok(response);
	}
}