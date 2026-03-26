package in.BidPilots.controller;

import in.BidPilots.dto.SaveFilterRequest;
import in.BidPilots.entity.User;
import in.BidPilots.repository.UserRegistrationRepository;
import in.BidPilots.service.UserSavedFilterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user/filters")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class UserSavedFilterController {

    private final UserSavedFilterService savedFilterService;
    private final UserRegistrationRepository userRepository;

    // ------------------------------------------------------------------
    // Helper: resolve the authenticated user's ID from the JWT principal.
    // Returns null and writes a 401 body if not authenticated.
    // ------------------------------------------------------------------
    private Long resolveUserId(Authentication auth, Map<String, Object> errorOut) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            errorOut.put("success", false);
            errorOut.put("message", "Not authenticated");
            return null;
        }
        try {
            String email = auth.getName();
            return userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"))
                    .getId();
        } catch (Exception e) {
            errorOut.put("success", false);
            errorOut.put("message", "User not found");
            return null;
        }
    }

    /**
     * POST /api/user/filters/save
     *
     * FIX: userId is resolved from the JWT, not accepted from the request body.
     * Added @Valid so Bean Validation annotations on SaveFilterRequest are enforced.
     */
    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> saveFilter(
            @Valid @RequestBody SaveFilterRequest request) {

        Map<String, Object> errResponse = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = resolveUserId(auth, errResponse);
        if (userId == null) return ResponseEntity.status(401).body(errResponse);

        log.info("📝 Save filter request from user: {}", userId);

        // FIX: Pass userId from JWT, not from request body
        Map<String, Object> response = savedFilterService.saveFilter(userId, request);

        return response.containsKey("success") && Boolean.TRUE.equals(response.get("success"))
                ? ResponseEntity.ok(response)
                : ResponseEntity.badRequest().body(response);
    }

    /**
     * GET /api/user/filters/my
     *
     * FIX: Replaced /user/{userId} path-variable endpoint. Anyone could enumerate
     * another user's filters by changing {userId}. Now the user ID always comes from
     * the JWT — no path variable needed.
     */
    @GetMapping("/my")
    public ResponseEntity<Map<String, Object>> getMyFilters() {
        Map<String, Object> errResponse = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = resolveUserId(auth, errResponse);
        if (userId == null) return ResponseEntity.status(401).body(errResponse);

        log.info("📋 Fetching filters for user: {}", userId);
        Map<String, Object> response = savedFilterService.getUserFilters(userId);

        return response.containsKey("success") && Boolean.TRUE.equals(response.get("success"))
                ? ResponseEntity.ok(response)
                : ResponseEntity.badRequest().body(response);
    }

    /**
     * GET /api/user/filters/{filterId}
     *
     * FIX: Now verifies the filter belongs to the requesting user
     * (ownership check inside the service).
     */
    @GetMapping("/{filterId}")
    public ResponseEntity<Map<String, Object>> getFilterById(@PathVariable Long filterId) {
        Map<String, Object> errResponse = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = resolveUserId(auth, errResponse);
        if (userId == null) return ResponseEntity.status(401).body(errResponse);

        log.info("🔍 Fetching filter {} for user {}", filterId, userId);

        // FIX: pass userId so the service can enforce ownership
        Map<String, Object> response = savedFilterService.getFilterById(userId, filterId);

        return response.containsKey("success") && Boolean.TRUE.equals(response.get("success"))
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(404).body(response);
    }

    /**
     * DELETE /api/user/filters/{filterId}
     *
     * FIX: Removed {userId} from path — resolved from JWT.
     * The service layer enforces ownership atomically.
     */
    @DeleteMapping("/{filterId}")
    public ResponseEntity<Map<String, Object>> deleteFilter(@PathVariable Long filterId) {
        Map<String, Object> errResponse = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = resolveUserId(auth, errResponse);
        if (userId == null) return ResponseEntity.status(401).body(errResponse);

        log.info("🗑️ Delete filter {} for user {}", filterId, userId);
        Map<String, Object> response = savedFilterService.deleteFilter(userId, filterId);

        return response.containsKey("success") && Boolean.TRUE.equals(response.get("success"))
                ? ResponseEntity.ok(response)
                : ResponseEntity.badRequest().body(response);
    }

    /**
     * GET /api/user/filters/my/type/{filterType}
     */
    @GetMapping("/my/type/{filterType}")
    public ResponseEntity<Map<String, Object>> getMyFiltersByType(
            @PathVariable String filterType) {

        Map<String, Object> errResponse = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = resolveUserId(auth, errResponse);
        if (userId == null) return ResponseEntity.status(401).body(errResponse);

        log.info("📋 Fetching {} filters for user {}", filterType, userId);
        Map<String, Object> response = savedFilterService.getUserFiltersByType(userId, filterType);

        return response.containsKey("success") && Boolean.TRUE.equals(response.get("success"))
                ? ResponseEntity.ok(response)
                : ResponseEntity.badRequest().body(response);
    }

    /**
     * POST /api/user/filters/{filterId}/used
     */
    @PostMapping("/{filterId}/used")
    public ResponseEntity<Map<String, Object>> updateLastUsed(@PathVariable Long filterId) {
        Map<String, Object> errResponse = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = resolveUserId(auth, errResponse);
        if (userId == null) return ResponseEntity.status(401).body(errResponse);

        log.info("🕒 Update last used for filter {}", filterId);

        // FIX: pass userId so the service verifies ownership
        Map<String, Object> response = savedFilterService.updateFilterLastUsed(userId, filterId);

        return response.containsKey("success") && Boolean.TRUE.equals(response.get("success"))
                ? ResponseEntity.ok(response)
                : ResponseEntity.badRequest().body(response);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "User Saved Filter Service");
        response.put("timestamp", java.time.LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }
}