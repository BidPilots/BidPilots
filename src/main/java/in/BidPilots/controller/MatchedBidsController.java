package in.BidPilots.controller;

import in.BidPilots.dto.MatchedBidDTO;
import in.BidPilots.entity.Bid;
import in.BidPilots.entity.MatchedBids;
import in.BidPilots.entity.User;
import in.BidPilots.repository.BidRepository;
import in.BidPilots.repository.MatchedBidsRepository;
import in.BidPilots.repository.UserRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

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
    private final UserRegistrationRepository userRepository;

    // ------------------------------------------------------------------
    // Helper: resolve user ID from JWT, write 401 body on failure.
    // ------------------------------------------------------------------
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

    @GetMapping
    public ResponseEntity<Map<String, Object>> getMatchedBids() {
        log.info("📋 Fetching matched bids for current user");

        Map<String, Object> errResponse = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = resolveUserId(auth, errResponse);
        if (userId == null) return ResponseEntity.status(401).body(errResponse);

        Map<String, Object> response = new HashMap<>();

        try {
            List<MatchedBids> matches = matchedBidsRepository.findByUserIdOrderByMatchedAtDesc(userId);

            // FIX: Collect all bid IDs first, then fetch in ONE query (eliminates N+1).
            // Original code did bidRepository.findById(match.getBidId()) inside a loop —
            // one SELECT per match row, which destroys performance at scale.
            Set<Long> bidIds = matches.stream()
                    .map(MatchedBids::getBidId)
                    .collect(Collectors.toSet());

            Map<Long, Bid> bidMap = bidRepository.findAllById(bidIds)
                    .stream()
                    .collect(Collectors.toMap(Bid::getId, b -> b));

            List<MatchedBidDTO> dtos = matches.stream()
                    .map(m -> MatchedBidDTO.fromEntity(m, bidMap.get(m.getBidId())))
                    .collect(Collectors.toList());

            long unviewedCount = matches.stream().filter(m -> !m.getIsViewed()).count();

            response.put("success", true);
            response.put("matches", dtos);
            response.put("total", dtos.size());
            response.put("unviewed", unviewedCount);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error fetching matched bids: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to fetch matched bids: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

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

    /**
     * Bulk mark-as-viewed.
     * FIX: Only marks records that belong to the authenticated user
     * (the JPQL in markAsViewed already filters by userId).
     */
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

    /**
     * Single mark-as-viewed.
     * FIX: Ownership check is preserved.
     */
    @PostMapping("/{matchId}/viewed")
    public ResponseEntity<Map<String, Object>> markSingleAsViewed(@PathVariable Long matchId) {
        Map<String, Object> errResponse = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = resolveUserId(auth, errResponse);
        if (userId == null) return ResponseEntity.status(401).body(errResponse);

        Map<String, Object> response = new HashMap<>();

        try {
            MatchedBids match = matchedBidsRepository.findById(matchId).orElse(null);
            if (match == null) {
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
}