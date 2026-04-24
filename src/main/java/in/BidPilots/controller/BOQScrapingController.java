package in.BidPilots.controller;

import in.BidPilots.entity.Boq;
import in.BidPilots.repository.BoqRepository;
import in.BidPilots.service.BOQScrapingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/admin/boq")
@RequiredArgsConstructor
@Slf4j
public class BOQScrapingController {

    private final BOQScrapingService boqScrapingService;
    private final BoqRepository boqRepository;

    @PostMapping("/scrape")
    public ResponseEntity<Map<String, Object>> startScraping() {
        log.info("BOQ scrape triggered via REST");
        
        Map<String, Object> progress = boqScrapingService.getProgress();
        if (Boolean.TRUE.equals(progress.get("isActive"))) {
            return ResponseEntity.status(409).body(Map.of(
                "success", false,
                "message", "BOQ scraping already in progress. Please wait.",
                "progress", progress
            ));
        }
        
        CompletableFuture.runAsync(() -> {
            try {
                boqScrapingService.scrapeAllBoqs();
            } catch (Exception e) {
                log.error("Async BOQ scraping error: {}", e.getMessage(), e);
            }
        });
        
        return ResponseEntity.accepted().body(Map.of(
            "success", true,
            "message", "BOQ scraping started in background. Poll /api/admin/boq/scrape/progress for updates."
        ));
    }

    @GetMapping("/scrape/progress")
    public ResponseEntity<Map<String, Object>> getProgress() {
        return ResponseEntity.ok(boqScrapingService.getProgress());
    }

    @GetMapping("/scrape/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(boqScrapingService.getProgress());
    }

    @GetMapping("/list")
    public ResponseEntity<List<Boq>> listAll() {
        List<Boq> boqs = boqRepository.findAllByOrderByCreatedAtDesc();
        return ResponseEntity.ok(boqs);
    }

    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> count() {
        long total = boqRepository.count();
        long active = boqRepository.countByIsActiveTrue();
        return ResponseEntity.ok(Map.of(
            "count", total,
            "total", total,
            "active", active,
            "inactive", total - active
        ));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        long total = boqRepository.count();
        long active = boqRepository.countByIsActiveTrue();
        
        Map<String, Object> progress = boqScrapingService.getProgress();
        Object lastRun = progress.get("lastRun");
        
        Map<String, Object> response = new HashMap<>();
        response.put("total", total);
        response.put("active", active);
        response.put("inactive", total - active);
        response.put("lastRun", lastRun != null ? lastRun : "Never");
        
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Boq patch) {
        return boqRepository.findById(id).map(boq -> {
            if (patch.getBoqTitle() != null && !patch.getBoqTitle().trim().isEmpty()) {
                // Check for duplicate title
                if (!patch.getBoqTitle().equals(boq.getBoqTitle()) && 
                    boqRepository.existsByBoqTitleIgnoreCase(patch.getBoqTitle().trim())) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "error", "BOQ with title '" + patch.getBoqTitle() + "' already exists"
                    ));
                }
                boq.setBoqTitle(patch.getBoqTitle().trim());
            }
            
            if (patch.getGemBoqId() != null) {
                String gemId = patch.getGemBoqId().trim();
                if (!gemId.equals(boq.getGemBoqId()) && gemId.length() > 0) {
                    if (boqRepository.existsByGemBoqId(gemId)) {
                        return ResponseEntity.badRequest().body(Map.of(
                            "error", "Duplicate GeM BOQ ID: " + gemId
                        ));
                    }
                    boq.setGemBoqId(gemId.isEmpty() ? null : gemId);
                }
            }
            
            if (patch.getIsActive() != null) {
                boq.setIsActive(patch.getIsActive());
            }
            
            Boq saved = boqRepository.save(boq);
            log.info("BOQ {} updated: title={}, active={}", id, saved.getBoqTitle(), saved.getIsActive());
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        if (!boqRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        
        boqRepository.deleteById(id);
        log.info("BOQ {} deleted", id);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "BOQ deleted successfully"
        ));
    }
    
    @GetMapping("/search")
    public ResponseEntity<List<Boq>> search(@RequestParam String q) {
        if (q == null || q.trim().isEmpty()) {
            return ResponseEntity.ok(boqRepository.findAllByOrderByCreatedAtDesc());
        }
        return ResponseEntity.ok(boqRepository.searchByTitleOrGemId(q.trim()));
    }
    
    @DeleteMapping("/delete-all")
    public ResponseEntity<Map<String, Object>> deleteAll() {
        long count = boqRepository.count();
        boqRepository.deleteAll();
        log.info("Deleted all {} BOQ entries", count);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Deleted " + count + " BOQ entries",
            "deletedCount", count
        ));
    }
}