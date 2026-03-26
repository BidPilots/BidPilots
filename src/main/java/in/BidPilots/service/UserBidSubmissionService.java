package in.BidPilots.service;

import in.BidPilots.dto.submission.BidSubmissionRequest;
import in.BidPilots.dto.submission.BidSubmissionResponse;
import in.BidPilots.entity.UserBidSubmission;
import in.BidPilots.repository.UserBidSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserBidSubmissionService {

    private final UserBidSubmissionRepository submissionRepository;

    // ── Save or Update ────────────────────────────────────────────────────────
    /**
     * Creates a new submission OR updates an existing one.
     * If the user already has a submission for this bid, it is updated in place.
     * Status transitions allowed:
     *   DRAFT → DRAFT      (keep editing)
     *   DRAFT → SUBMITTED  (finalise)
     *   SUBMITTED → DRAFT  (reopen — allowed until bid closes)
     */
    @Transactional
    public Map<String, Object> saveOrUpdate(Long userId, BidSubmissionRequest req) {
        Map<String, Object> response = new HashMap<>();
        try {
            Optional<UserBidSubmission> existing =
                    submissionRepository.findByUserIdAndBidId(userId, req.getBidId());

            UserBidSubmission sub = existing.orElseGet(UserBidSubmission::new);

            // Only set these on create
            if (sub.getId() == null) {
                sub.setUserId(userId);
                sub.setBidId(req.getBidId());
            }

            sub.setBidNumber(req.getBidNumber());
            sub.setBidItems(req.getBidItems());
            sub.setQuotedPrice(req.getQuotedPrice());
            sub.setTotalPrice(req.getTotalPrice());
            sub.setQuantity(req.getQuantity());
            sub.setNotes(req.getNotes());

            String newStatus = req.getStatus() != null ? req.getStatus() : "DRAFT";
            sub.setStatus(newStatus);

            // Stamp submittedAt when first moved to SUBMITTED
            if ("SUBMITTED".equals(newStatus) && sub.getSubmittedAt() == null) {
                sub.setSubmittedAt(LocalDateTime.now());
            }
            // Clear submittedAt if pulled back to DRAFT
            if ("DRAFT".equals(newStatus)) {
                sub.setSubmittedAt(null);
            }

            UserBidSubmission saved = submissionRepository.save(sub);
            log.info("✅ Bid submission saved: user={} bid={} status={}",
                    userId, req.getBidId(), newStatus);

            response.put("success", true);
            response.put("message", "SUBMITTED".equals(newStatus)
                    ? "Bid price submitted successfully"
                    : "Draft saved successfully");
            response.put("submission", BidSubmissionResponse.from(saved));

        } catch (Exception e) {
            log.error("Error saving bid submission: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to save: " + e.getMessage());
        }
        return response;
    }

    // ── Get all for user ─────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public Map<String, Object> getUserSubmissions(Long userId) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<BidSubmissionResponse> list = submissionRepository
                    .findActiveByUserId(userId)
                    .stream()
                    .map(BidSubmissionResponse::from)
                    .collect(Collectors.toList());

            long submitted = list.stream()
                    .filter(s -> "SUBMITTED".equals(s.getStatus())).count();

            response.put("success",    true);
            response.put("submissions", list);
            response.put("total",       list.size());
            response.put("submitted",   submitted);
            response.put("drafts",      list.size() - submitted);
        } catch (Exception e) {
            log.error("Error fetching submissions: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to fetch submissions");
        }
        return response;
    }

    // ── Get single submission for a bid ───────────────────────────────────────
    @Transactional(readOnly = true)
    public Map<String, Object> getForBid(Long userId, Long bidId) {
        Map<String, Object> response = new HashMap<>();
        try {
            Optional<UserBidSubmission> sub =
                    submissionRepository.findByUserIdAndBidId(userId, bidId);
            if (sub.isEmpty()) {
                response.put("success", true);
                response.put("exists",  false);
            } else {
                response.put("success",    true);
                response.put("exists",     true);
                response.put("submission", BidSubmissionResponse.from(sub.get()));
            }
        } catch (Exception e) {
            log.error("Error fetching submission: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to fetch submission");
        }
        return response;
    }

    // ── Delete (withdraw) ─────────────────────────────────────────────────────
    /**
     * Hard-deletes the submission row. The user can re-submit later.
     * Returns 404 if the submission doesn't belong to the user.
     */
    @Transactional
    public Map<String, Object> delete(Long userId, Long submissionId) {
        Map<String, Object> response = new HashMap<>();
        try {
            int deleted = submissionRepository.deleteByIdAndUserId(submissionId, userId);
            if (deleted == 0) {
                response.put("success", false);
                response.put("message", "Submission not found or access denied");
                return response;
            }
            log.info("🗑 Submission {} deleted by user {}", submissionId, userId);
            response.put("success", true);
            response.put("message", "Submission withdrawn successfully");
        } catch (Exception e) {
            log.error("Error deleting submission: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to withdraw: " + e.getMessage());
        }
        return response;
    }

    // ── Stats for a bid (how many users submitted) ────────────────────────────
    @Transactional(readOnly = true)
    public Map<String, Object> getBidStats(Long bidId) {
        Map<String, Object> response = new HashMap<>();
        try {
            long count = submissionRepository.countSubmittedByBidId(bidId);
            response.put("success",        true);
            response.put("bidId",          bidId);
            response.put("submittedCount", count);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to fetch stats");
        }
        return response;
    }
}