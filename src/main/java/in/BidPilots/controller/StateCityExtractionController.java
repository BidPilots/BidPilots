package in.BidPilots.controller;

import in.BidPilots.service.StateCityExtractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/states-cities")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class StateCityExtractionController {

    private final StateCityExtractionService stateCityExtractionService;

    /**
     * ONE-TIME SETUP: Extract all states and cities from GeM portal
     * This should be run once before starting any scraping
     */
    @PostMapping("/extract")
    public ResponseEntity<Map<String, Object>> extractStatesAndCities() {
        log.info("🗺️ Manual state/city extraction requested");
        
        Map<String, Object> response = stateCityExtractionService.extractStatesAndCities();
        
        if (response.containsKey("success") && (Boolean) response.get("success")) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearStatesAndCities() {
        log.warn("⚠️ Request to clear all states and cities received");
        
        Map<String, Object> response = stateCityExtractionService.clearStatesAndCities();
        
        if (response.containsKey("success") && (Boolean) response.get("success")) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Check if states are populated (simple endpoint for dashboard)
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkStatesPopulated() {
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> status = stateCityExtractionService.getStatus();
        
        response.put("success", true);
        response.put("statesPopulated", status.get("statesPopulated"));
        response.put("totalStates", status.get("totalStates"));
        response.put("totalCities", status.get("totalCities"));
        
        return ResponseEntity.ok(response);
    }
}