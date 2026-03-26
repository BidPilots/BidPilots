package in.BidPilots.controller;

import in.BidPilots.dto.submission.BidSubmissionRequest;
import in.BidPilots.entity.User;
import in.BidPilots.repository.UserRegistrationRepository;
import in.BidPilots.service.UserBidSubmissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for user bid price submissions.
 * All endpoints live under /api/user/submissions — completely separate
 * from the existing /api/user/bids and /api/user/matches endpoints.
 *
 * SecurityConfig already covers /api/user/** with hasRole("USER"),
 * so no extra security config needed.
 */
@RestController
@RequestMapping("/api/user/submissions")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class UserBidSubmissionController {

    private final UserBidSubmissionService submissionService;
    private final UserRegistrationRepository userRepository;

    // ── Helper: resolve userId from JWT ───────────────────────────────────────
    private Long resolveUserId(Authentication auth, Map<String, Object> err) {
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            err.put("success", false);
            err.put("message", "Not authenticated");
            return null;
        }
        try {
            User user = userRepository.findByEmail(auth.getName())
                    .orElseThrow(() -> new RuntimeException("User not found"));
            return user.getId();
        } catch (Exception e) {
            err.put("success", false);
            err.put("message", "User not found");
            return null;
        }
    }

    // ── POST /api/user/submissions ────────────────────────────────────────────
    /**
     * Save or update a bid price submission.
     * Body: { bidId, bidNumber, bidItems, quotedPrice, totalPrice, quantity, notes, status }
     * status = "DRAFT" (save only) or "SUBMITTED" (finalise).
     * If a submission already exists for this user+bid, it is updated.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> saveOrUpdate(
            @Valid @RequestBody BidSubmissionRequest request) {

        java.util.Map<String, Object> err = new java.util.HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = resolveUserId(auth, err);
        if (userId == null) return ResponseEntity.status(401).body(err);

        log.info("💰 Submission save: user={} bid={} status={}",
                userId, request.getBidId(), request.getStatus());

        Map<String, Object> result = submissionService.saveOrUpdate(userId, request);
        boolean ok = Boolean.TRUE.equals(result.get("success"));
        return ok ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    // ── GET /api/user/submissions ─────────────────────────────────────────────
    /**
     * Get all submissions (DRAFT + SUBMITTED) for the logged-in user.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getMySubmissions() {
        java.util.Map<String, Object> err = new java.util.HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = resolveUserId(auth, err);
        if (userId == null) return ResponseEntity.status(401).body(err);

        Map<String, Object> result = submissionService.getUserSubmissions(userId);
        return ResponseEntity.ok(result);
    }

    // ── GET /api/user/submissions/bid/{bidId} ─────────────────────────────────
    /**
     * Get the current user's submission for a specific bid.
     * Returns { success, exists: false } if no submission exists yet.
     * Returns { success, exists: true, submission: {...} } if one exists.
     */
    @GetMapping("/bid/{bidId}")
    public ResponseEntity<Map<String, Object>> getForBid(@PathVariable Long bidId) {
        java.util.Map<String, Object> err = new java.util.HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = resolveUserId(auth, err);
        if (userId == null) return ResponseEntity.status(401).body(err);

        Map<String, Object> result = submissionService.getForBid(userId, bidId);
        return ResponseEntity.ok(result);
    }

    // ── DELETE /api/user/submissions/{id} ─────────────────────────────────────
    /**
     * Withdraw (hard-delete) a submission by its submission ID.
     * Only the owner can delete their own submission.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> withdraw(@PathVariable Long id) {
        java.util.Map<String, Object> err = new java.util.HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = resolveUserId(auth, err);
        if (userId == null) return ResponseEntity.status(401).body(err);

        log.info("🗑 Withdraw submission id={} user={}", id, userId);
        Map<String, Object> result = submissionService.delete(userId, id);
        boolean ok = Boolean.TRUE.equals(result.get("success"));
        return ok ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    // ── GET /api/user/submissions/stats/bid/{bidId} ───────────────────────────
    /**
     * How many users have submitted a price for this bid.
     * Useful to show "X bidders" on the bid card.
     */
    @GetMapping("/stats/bid/{bidId}")
    public ResponseEntity<Map<String, Object>> bidStats(@PathVariable Long bidId) {
        Map<String, Object> result = submissionService.getBidStats(bidId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status",  "UP",
            "service", "UserBidSubmission Service"
        ));
    }
}