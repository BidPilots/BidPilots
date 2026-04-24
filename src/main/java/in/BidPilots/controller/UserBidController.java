package in.BidPilots.controller;

import in.BidPilots.entity.Bid;
import in.BidPilots.entity.BidDetails;
import in.BidPilots.entity.MatchedBids;
import in.BidPilots.entity.User;
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
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class UserBidController {

    private final BidRepository bidRepository;
    private final MatchedBidsRepository matchedBidsRepository;
    private final UserRegistrationRepository userRepository;
    private final BidDetailsRepository bidDetailsRepository; // ADD THIS

    // ── Auth helper ───────────────────────────────────────────────────────────
    private User resolveUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return userRepository.findByEmail(auth.getName()).orElse(null);
    }

    // ── Core bid-building logic (shared by /bids and /matches) ─────────────────
    private Map<String, Object> buildBidResponse(Long userId) {
        Map<String, Object> response = new HashMap<>();

        // Soft-deleted (dismissed) rows are excluded by the repo query
        List<MatchedBids> matches = matchedBidsRepository.findByUserId(userId);

        Set<Long> matchedBidIds = matches.stream()
                .map(MatchedBids::getBidId)
                .collect(Collectors.toSet());

        List<Bid> userBids = bidRepository.findAllById(matchedBidIds);

        // FETCH BID DETAILS FOR PRE-BID DATE TIME
        Map<Long, BidDetails> bidDetailsMap = new HashMap<>();
        if (!matchedBidIds.isEmpty()) {
            List<BidDetails> bidDetailsList = bidDetailsRepository.findAllByBidIdIn(matchedBidIds);
            bidDetailsMap = bidDetailsList.stream()
                    .collect(Collectors.toMap(bd -> bd.getBid().getId(), bd -> bd));
        }

        Map<Long, MatchedBids> matchDetails = matches.stream()
                .collect(Collectors.toMap(MatchedBids::getBidId, m -> m));

        List<Map<String, Object>> enhancedBids = new ArrayList<>();
        for (Bid bid : userBids) {
            MatchedBids match = matchDetails.get(bid.getId());
            if (match == null) continue;

            // Get BidDetails for preBidDateTime
            BidDetails bidDetails = bidDetailsMap.get(bid.getId());

            Map<String, Object> bidMap = new HashMap<>();

            // ── Core fields ────────────────────────────────────────────────
            bidMap.put("id",          bid.getId());
            bidMap.put("bidNumber",   bid.getBidNumber());
            bidMap.put("raNumber",    bid.getRaNumber());
            bidMap.put("items",       bid.getItems());
            bidMap.put("quantity",    bid.getQuantity());
            bidMap.put("department",  bid.getDepartment());
            bidMap.put("ministry",    bid.getMinistry());
            bidMap.put("bidType",     bid.getBidType());
            bidMap.put("isActive",    bid.getIsActive());
            bidMap.put("isFinalized", bid.getIsFinalized());

            // ── Document URLs ──────────────────────────────────────────────
            bidMap.put("bidDocumentUrl", bid.getBidDocumentUrl());
            bidMap.put("raDocumentUrl",  bid.getRaDocumentUrl());

            // ── Regular bid dates ──────────────────────────────────────────
            bidMap.put("bidStartDate", bid.getBidStartDate());
            bidMap.put("bidEndDate",   bid.getBidEndDate());

            // ── RA-specific dates ───────────────────────────────────────────
            bidMap.put("raStartDate", bid.getRaStartDate());
            bidMap.put("raEndDate",   bid.getRaEndDate());

            // ── PRE-BID DATE TIME FROM BID DETAILS ───────────────────────────
            if (bidDetails != null) {
                bidMap.put("preBidDateTime", bidDetails.getPreBidDateTime());
            } else {
                bidMap.put("preBidDateTime", null);
            }

            // ── Location ───────────────────────────────────────────────────
            bidMap.put("state", bid.getState() != null ? bid.getState().getStateName() : null);
            bidMap.put("city",  bid.getConsigneeCity() != null ? bid.getConsigneeCity().getCityName() : null);

            // ── Match metadata ─────────────────────────────────────────────
            bidMap.put("matchId",    match.getId());
            bidMap.put("matchedAt",  match.getMatchedAt());
            bidMap.put("filterId",   match.getFilterId());
            bidMap.put("categoryId", match.getCategoryId());
            bidMap.put("isViewed",   match.getIsViewed());

            enhancedBids.add(bidMap);
        }

        // Newest-matched first
        enhancedBids.sort((a, b) -> {
            LocalDateTime dateA = (LocalDateTime) a.get("matchedAt");
            LocalDateTime dateB = (LocalDateTime) b.get("matchedAt");
            if (dateA == null && dateB == null) return 0;
            if (dateA == null) return 1;
            if (dateB == null) return -1;
            return dateB.compareTo(dateA);
        });

        long unviewedCount = matches.stream().filter(m -> !m.getIsViewed()).count();

        response.put("success",  true);
        response.put("bids",     enhancedBids);
        response.put("matches",  enhancedBids);
        response.put("total",    enhancedBids.size());
        response.put("unviewed", unviewedCount);

        return response;
    }

    // =========================================================================
    //  GET /api/user/bids
    // =========================================================================
    @GetMapping("/bids")
    public ResponseEntity<Map<String, Object>> getUserBids() {
        log.info("📋 GET /api/user/bids");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = resolveUser(auth);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated"));
        }
        try {
            return ResponseEntity.ok(buildBidResponse(user.getId()));
        } catch (Exception e) {
            log.error("Error fetching user bids: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "message", "Failed to fetch bids: " + e.getMessage()));
        }
    }

    // =========================================================================
    //  GET /api/user/matches
    // =========================================================================
    @GetMapping("/matches")
    public ResponseEntity<Map<String, Object>> getUserMatches() {
        log.info("📋 GET /api/user/matches");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = resolveUser(auth);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated"));
        }
        try {
            return ResponseEntity.ok(buildBidResponse(user.getId()));
        } catch (Exception e) {
            log.error("Error fetching matched bids: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "message", "Failed to fetch matched bids: " + e.getMessage()));
        }
    }

    // =========================================================================
    //  GET /api/user/bids/stats
    // =========================================================================
    @GetMapping("/bids/stats")
    public ResponseEntity<Map<String, Object>> getUserBidStats() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = resolveUser(auth);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated"));
        }

        Map<String, Object> response = new HashMap<>();
        try {
            List<MatchedBids> matches  = matchedBidsRepository.findByUserId(user.getId());
            Set<Long> matchedBidIds    = matches.stream().map(MatchedBids::getBidId).collect(Collectors.toSet());
            List<Bid> userBids         = bidRepository.findAllById(matchedBidIds);
            LocalDateTime now          = LocalDateTime.now();
            LocalDateTime threeDaysLater = now.plusDays(3);

            long active = userBids.stream().filter(b -> {
                if (b.getIsActive() == null || !b.getIsActive()) return false;
                LocalDateTime end = effectiveEnd(b);
                return end != null && end.isAfter(now);
            }).count();

            long closed = userBids.stream().filter(b -> {
                if (b.getIsActive() == null || !b.getIsActive()) return true;
                LocalDateTime end = effectiveEnd(b);
                return end == null || !end.isAfter(now);
            }).count();

            long ra = userBids.stream()
                    .filter(b -> "BID_TO_RA".equals(b.getBidType())).count();

            long closingSoon = userBids.stream().filter(b -> {
                if (b.getIsActive() == null || !b.getIsActive()) return false;
                LocalDateTime end = effectiveEnd(b);
                return end != null && end.isAfter(now) && end.isBefore(threeDaysLater);
            }).count();

            response.put("success",    true);
            response.put("active",     active);
            response.put("closed",     closed);
            response.put("ra",         ra);
            response.put("closingSoon", closingSoon);
            response.put("total",      userBids.size());
            response.put("unviewed",   matches.stream().filter(m -> !m.getIsViewed()).count());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error fetching bid stats: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to fetch stats: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    private LocalDateTime effectiveEnd(Bid bid) {
        if ("BID_TO_RA".equals(bid.getBidType())) {
            return bid.getRaEndDate() != null ? bid.getRaEndDate() : bid.getBidEndDate();
        }
        return bid.getBidEndDate();
    }

    // =========================================================================
    //  POST /api/user/bids/{bidId}/view
    // =========================================================================
    @PostMapping("/bids/{bidId}/view")
    public ResponseEntity<Map<String, Object>> markBidAsViewed(@PathVariable Long bidId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = resolveUser(auth);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated"));
        }
        Map<String, Object> response = new HashMap<>();
        try {
            MatchedBids match = matchedBidsRepository.findByUserIdAndBidId(user.getId(), bidId)
                    .orElseThrow(() -> new RuntimeException("Match not found"));
            match.setIsViewed(true);
            matchedBidsRepository.save(match);
            response.put("success", true);
            response.put("message", "Bid marked as viewed");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error marking bid as viewed: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to mark bid as viewed: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // =========================================================================
    //  POST /api/user/matches/{matchId}/dismiss
    // =========================================================================
    @PostMapping("/matches/{matchId}/dismiss")
    public ResponseEntity<Map<String, Object>> dismissMatch(@PathVariable Long matchId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = resolveUser(auth);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated"));
        }
        int updated = matchedBidsRepository.softDeleteByIdAndUserId(matchId, user.getId());
        if (updated == 0) {
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "message", "Match not found or already dismissed"));
        }
        log.info("🗑 Match {} dismissed by user {}", matchId, user.getId());
        return ResponseEntity.ok(Map.of("success", true, "message", "Bid removed from your list"));
    }
}