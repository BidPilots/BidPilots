package in.BidPilots.controller;

import in.BidPilots.entity.Boq;
import in.BidPilots.repository.BoqRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Public endpoint that serves the BOQ catalogue to the filter UI.
 *
 * GET /api/boq
 *   Returns all active BOQ items ordered alphabetically by title.
 *   No authentication required — the list is reference data, not user-specific.
 *
 * GET /api/boq/search?q=gloves
 *   Searches boqTitle containing the query string (case-insensitive).
 *   Useful if the catalogue grows large and the frontend wants server-side filtering.
 */
@RestController
@RequestMapping("/api/boq")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class BoqController {

    private final BoqRepository boqRepository;

    /**
     * Returns every active BOQ record, ordered A→Z by title.
     *
     * Response shape:
     * {
     *   "success": true,
     *   "boqList": [
     *     { "id": 1, "boqTitle": "Nitrile Examination Gloves", "gemBoqId": "GEM/BOQ/...", ... },
     *     ...
     *   ],
     *   "total": 42
     * }
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllBoq() {
        Map<String, Object> response = new HashMap<>();
        try {
            List<Boq> boqList = boqRepository.findByIsActiveTrueOrderByBoqTitleAsc();
            response.put("success", true);
            response.put("boqList", boqList);
            response.put("total", boqList.size());
            log.debug("Served {} active BOQ items", boqList.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching BOQ list: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to fetch BOQ list");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Server-side search — handy when the catalogue is very large.
     *
     * Example: GET /api/boq/search?q=surgical
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchBoq(
            @RequestParam(defaultValue = "") String q) {

        Map<String, Object> response = new HashMap<>();
        try {
            List<Boq> results = q.isBlank()
                    ? boqRepository.findByIsActiveTrueOrderByBoqTitleAsc()
                    : boqRepository.findByIsActiveTrueAndBoqTitleContainingIgnoreCaseOrderByBoqTitleAsc(q.trim());

            response.put("success", true);
            response.put("boqList", results);
            response.put("total", results.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error searching BOQ list for '{}': {}", q, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to search BOQ list");
            return ResponseEntity.internalServerError().body(response);
        }
    }
}