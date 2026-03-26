package in.BidPilots.controller;

import in.BidPilots.dto.CategoryDTO;
import in.BidPilots.entity.Category;
import in.BidPilots.repository.CategoryRepository;
import in.BidPilots.service.CategoryScrapingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class CategoryController {

    private final CategoryScrapingService categoryScrapingService;
    private final CategoryRepository categoryRepository;

    /**
     * Scrape all categories from GeM portal
     */
    @PostMapping("/scrape")
    public ResponseEntity<Map<String, Object>> scrapeCategories() {
        log.info("📁 Manual category scraping requested");
        
        // Check authentication
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated"));
        }

        Map<String, Object> response = categoryScrapingService.scrapeAllCategories();

        if (response.containsKey("success") && (Boolean) response.get("success")) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Get scraping progress
     */
    @GetMapping("/scrape/progress")
    public ResponseEntity<Map<String, Object>> getScrapeProgress() {
        Map<String, Object> progress = categoryScrapingService.getProgress();
        progress.put("success", true);
        return ResponseEntity.ok(progress);
    }

    /**
     * Get all categories
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllCategories() {
        Map<String, Object> response = categoryScrapingService.getAllCategories();
        return ResponseEntity.ok(response);
    }

    /**
     * Get category by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getCategoryById(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        
        Category category = categoryRepository.findById(id).orElse(null);
        if (category == null) {
            response.put("success", false);
            response.put("message", "Category not found");
            return ResponseEntity.status(404).body(response);
        }

        response.put("success", true);
        response.put("category", CategoryDTO.fromCategory(category));
        return ResponseEntity.ok(response);
    }

    /**
     * Search categories by name
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchCategories(@RequestParam String keyword) {
        return ResponseEntity.ok(categoryScrapingService.searchCategories(keyword));
    }

    /**
     * Get categories count
     */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> getCategoriesCount() {
        return ResponseEntity.ok(categoryScrapingService.getCategoriesCount());
    }

    /**
     * Delete category by ID (admin only)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteCategory(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated"));
        }

        if (!categoryRepository.existsById(id)) {
            response.put("success", false);
            response.put("message", "Category not found");
            return ResponseEntity.status(404).body(response);
        }

        categoryRepository.deleteById(id);
        
        response.put("success", true);
        response.put("message", "Category deleted successfully");
        return ResponseEntity.ok(response);
    }

    /**
     * Clear all categories (danger zone)
     */
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearAllCategories() {
        log.warn("⚠️ Request to clear all categories received");
        
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated"));
        }

        long count = categoryRepository.count();
        categoryRepository.deleteAll();
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "All categories cleared");
        response.put("deletedCount", count);
        
        log.info("✅ Cleared {} categories", count);
        return ResponseEntity.ok(response);
    }

    /**
     * Health check
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "Category Service");
        response.put("categoriesCount", String.valueOf(categoryRepository.count()));
        response.put("timestamp", java.time.LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }
}