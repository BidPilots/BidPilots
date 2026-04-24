package in.BidPilots.controller;

import in.BidPilots.dto.MatchedBidDTO;
import in.BidPilots.entity.Bid;
import in.BidPilots.entity.BidDetails;
import in.BidPilots.entity.MatchedBids;
import in.BidPilots.repository.BidDetailsRepository;
import in.BidPilots.repository.BidRepository;
import in.BidPilots.repository.MatchedBidsRepository;
import in.BidPilots.repository.UserRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/user/matches")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class MatchedBidsController {

    private final MatchedBidsRepository matchedBidsRepository;
    private final BidRepository bidRepository;
    private final BidDetailsRepository bidDetailsRepository;
    private final UserRegistrationRepository userRepository;

    // ── Helper: resolve user ID from JWT ──────────────────────────────────────
    private Long resolveUserId(Authentication auth, Map<String, Object> errOut) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            errOut.put("success", false);
            errOut.put("message", "Not authenticated");
            return null;
        }
        try {
            return userRepository.findByEmail(auth.getName())
                    .orElseThrow(() -> new RuntimeException("User not found"))
                    .getId();
        } catch (Exception e) {
            errOut.put("success", false);
            errOut.put("message", "User not found");
            return null;
        }
    }

    // =========================================================================
    //  NOTE: GET /api/user/matches endpoint is REMOVED because UserBidController 
    //  already provides this with complete RA date fields (raStartDate, raEndDate).
    //  The frontend now uses UserBidController's /api/user/matches endpoint.
    // =========================================================================

    // ── GET /api/user/matches/unviewed/count ──────────────────────────────────
    @GetMapping("/unviewed/count")
    public ResponseEntity<Map<String, Object>> getUnviewedCount() {
        Map<String, Object> errResponse = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = resolveUserId(auth, errResponse);
        if (userId == null) return ResponseEntity.status(401).body(errResponse);

        Map<String, Object> response = new HashMap<>();

        try {
            long count = matchedBidsRepository.countUnviewedByUserId(userId);
            response.put("success", true);
            response.put("count", count);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error fetching unviewed count: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to fetch count: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // ── POST /api/user/matches/viewed ─────────────────────────────────────────
    @PostMapping("/viewed")
    public ResponseEntity<Map<String, Object>> markAsViewed(@RequestBody List<Long> matchIds) {
        Map<String, Object> errResponse = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = resolveUserId(auth, errResponse);
        if (userId == null) return ResponseEntity.status(401).body(errResponse);

        Map<String, Object> response = new HashMap<>();

        try {
            matchedBidsRepository.markAsViewed(userId, matchIds);
            response.put("success", true);
            response.put("message", "Matches marked as viewed");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error marking matches as viewed: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to mark matches: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // ── POST /api/user/matches/{matchId}/viewed ───────────────────────────────
    @PostMapping("/{matchId}/viewed")
    public ResponseEntity<Map<String, Object>> markSingleAsViewed(@PathVariable Long matchId) {
        Map<String, Object> errResponse = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = resolveUserId(auth, errResponse);
        if (userId == null) return ResponseEntity.status(401).body(errResponse);

        Map<String, Object> response = new HashMap<>();

        try {
            MatchedBids match = matchedBidsRepository.findById(matchId).orElse(null);
            if (match == null || Boolean.TRUE.equals(match.getIsDeleted())) {
                response.put("success", false);
                response.put("message", "Match not found");
                return ResponseEntity.status(404).body(response);
            }

            if (!match.getUserId().equals(userId)) {
                return ResponseEntity.status(403).body(
                        Map.of("success", false, "message", "Access denied"));
            }

            match.setIsViewed(true);
            matchedBidsRepository.save(match);

            response.put("success", true);
            response.put("message", "Match marked as viewed");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error marking match as viewed: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to mark match: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // ── DELETE /api/user/matches/{matchId} ───────────────────────────────
    /**
     * Soft-deletes (dismisses) a single matched bid for the authenticated user.
     *
     * The row is NOT physically removed — it is kept with isDeleted = true so that
     * the 15-minute scheduling job's existsByUserIdAndBidId check continues to
     * return true, permanently preventing the same bid from reappearing.
     *
     * Returns 404 if the match does not exist or belongs to a different user.
     */
    @DeleteMapping("/{matchId}")
    public ResponseEntity<Map<String, Object>> dismissMatch(@PathVariable Long matchId) {
        Map<String, Object> errResponse = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = resolveUserId(auth, errResponse);
        if (userId == null) return ResponseEntity.status(401).body(errResponse);

        Map<String, Object> response = new HashMap<>();

        try {
            int updated = matchedBidsRepository.softDeleteByIdAndUserId(matchId, userId);

            if (updated == 0) {
                response.put("success", false);
                response.put("message", "Match not found or already dismissed");
                return ResponseEntity.status(404).body(response);
            }

            log.info("User {} dismissed matched bid matchId={}", userId, matchId);
            response.put("success", true);
            response.put("message", "Bid dismissed. It will not reappear in future matches.");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error dismissing match {} for user {}: {}", matchId, userId, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to dismiss match: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // ── DELETE /api/user/matches (bulk dismiss) ─────────────────────────
    /**
     * Soft-deletes multiple matched bids for the authenticated user in one call.
     * Request body: JSON array of match IDs, e.g. [1, 5, 9]
     *
     * Each dismissed bid is kept in matched_bids with isDeleted = true,
     * so the 15-min scheduler will never re-insert any of them.
     */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> dismissMatches(@RequestBody List<Long> matchIds) {
        Map<String, Object> errResponse = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = resolveUserId(auth, errResponse);
        if (userId == null) return ResponseEntity.status(401).body(errResponse);

        Map<String, Object> response = new HashMap<>();

        if (matchIds == null || matchIds.isEmpty()) {
            response.put("success", false);
            response.put("message", "No match IDs provided");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            int updated = matchedBidsRepository.softDeleteByIdsAndUserId(matchIds, userId);

            log.info("User {} dismissed {} matched bid(s)", userId, updated);
            response.put("success", true);
            response.put("dismissed", updated);
            response.put("message", updated + " bid(s) dismissed. They will not reappear.");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error bulk-dismissing matches for user {}: {}", userId, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to dismiss matches: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
}