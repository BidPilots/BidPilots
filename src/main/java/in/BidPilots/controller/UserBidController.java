package in.BidPilots.controller;

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

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/user/bids")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class UserBidController {

    private final BidRepository bidRepository;
    private final MatchedBidsRepository matchedBidsRepository;
    private final UserRegistrationRepository userRepository;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getUserBids() {
        log.info("📋 Fetching bids for current user");
        
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated"));
        }

        Map<String, Object> response = new HashMap<>();
        
        try {
            // Get user ID from email
            String email = auth.getName();
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));
            
            Long userId = user.getId();
            
            // Get all matched bids for this user
            List<MatchedBids> matches = matchedBidsRepository.findByUserId(userId);
            
            // Extract bid IDs from matches
            Set<Long> matchedBidIds = matches.stream()
                    .map(MatchedBids::getBidId)
                    .collect(Collectors.toSet());
            
            // Get all bids that are matched to this user
            List<Bid> userBids = bidRepository.findAllById(matchedBidIds);
            
            // Create a map of match details for each bid
            Map<Long, MatchedBids> matchDetails = matches.stream()
                    .collect(Collectors.toMap(MatchedBids::getBidId, m -> m));
            
            // Enhance bid objects with match information
            List<Map<String, Object>> enhancedBids = new ArrayList<>();
            for (Bid bid : userBids) {
                MatchedBids match = matchDetails.get(bid.getId());
                if (match != null) {
                    Map<String, Object> bidMap = new HashMap<>();
                    bidMap.put("id", bid.getId());
                    bidMap.put("bidNumber", bid.getBidNumber());
                    bidMap.put("raNumber", bid.getRaNumber());
                    bidMap.put("items", bid.getItems());
                    bidMap.put("quantity", bid.getQuantity());
                    bidMap.put("department", bid.getDepartment());
                    bidMap.put("ministry", bid.getMinistry());
                    bidMap.put("bidStartDate", bid.getBidStartDate());
                    bidMap.put("bidEndDate", bid.getBidEndDate());
                    bidMap.put("bidType", bid.getBidType());
                    bidMap.put("state", bid.getState() != null ? bid.getState().getStateName() : null);
                    bidMap.put("city", bid.getConsigneeCity() != null ? bid.getConsigneeCity().getCityName() : null);
                    bidMap.put("isActive", bid.getIsActive());
                    bidMap.put("isFinalized", bid.getIsFinalized());
                    bidMap.put("bidDocumentUrl", bid.getBidDocumentUrl());
                    
                    // Add match information
                    bidMap.put("matchedAt", match.getMatchedAt());
                    bidMap.put("filterId", match.getFilterId());
                    bidMap.put("categoryId", match.getCategoryId());
                    bidMap.put("isViewed", match.getIsViewed());
                    
                    enhancedBids.add(bidMap);
                }
            }
            
            // Sort by match date (newest first)
            enhancedBids.sort((a, b) -> {
                LocalDateTime dateA = (LocalDateTime) a.get("matchedAt");
                LocalDateTime dateB = (LocalDateTime) b.get("matchedAt");
                return dateB.compareTo(dateA);
            });
            
            long unviewedCount = matches.stream().filter(m -> !m.getIsViewed()).count();
            
            response.put("success", true);
            response.put("bids", enhancedBids);
            response.put("total", enhancedBids.size());
            response.put("unviewed", unviewedCount);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error fetching user bids: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to fetch bids: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getUserBidStats() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated"));
        }

        Map<String, Object> response = new HashMap<>();
        
        try {
            String email = auth.getName();
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));
            
            Long userId = user.getId();
            
            // Get all matched bids for this user
            List<MatchedBids> matches = matchedBidsRepository.findByUserId(userId);
            
            // Get all bids that are matched to this user
            Set<Long> matchedBidIds = matches.stream()
                    .map(MatchedBids::getBidId)
                    .collect(Collectors.toSet());
            
            List<Bid> userBids = bidRepository.findAllById(matchedBidIds);
            
            // Calculate stats
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime threeDaysLater = now.plusDays(3);
            
            long active = userBids.stream()
                    .filter(b -> b.getIsActive() != null && b.getIsActive() && 
                           b.getBidEndDate() != null && b.getBidEndDate().isAfter(now))
                    .count();
            
            long closed = userBids.stream()
                    .filter(b -> b.getIsActive() == null || !b.getIsActive() || 
                           (b.getBidEndDate() != null && b.getBidEndDate().isBefore(now)))
                    .count();
            
            long ra = userBids.stream()
                    .filter(b -> "BID_TO_RA".equals(b.getBidType()))
                    .count();
            
            long closingSoon = userBids.stream()
                    .filter(b -> b.getIsActive() != null && b.getIsActive() &&
                           b.getBidEndDate() != null && 
                           b.getBidEndDate().isAfter(now) && 
                           b.getBidEndDate().isBefore(threeDaysLater))
                    .count();
            
            response.put("success", true);
            response.put("active", active);
            response.put("closed", closed);
            response.put("ra", ra);
            response.put("closingSoon", closingSoon);
            response.put("total", userBids.size());
            response.put("unviewed", matches.stream().filter(m -> !m.getIsViewed()).count());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error fetching user bid stats: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to fetch stats: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping("/{bidId}/view")
    public ResponseEntity<Map<String, Object>> markBidAsViewed(@PathVariable Long bidId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated"));
        }

        Map<String, Object> response = new HashMap<>();
        
        try {
            String email = auth.getName();
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));
            
            // Find the match record
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
}