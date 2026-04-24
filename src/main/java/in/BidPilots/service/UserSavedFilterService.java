package in.BidPilots.service;

import in.BidPilots.dto.FilterResponseDTO;
import in.BidPilots.dto.SaveFilterRequest;
import in.BidPilots.entity.UserSavedFilter;
import in.BidPilots.repository.UserSavedFilterRepository;
import in.BidPilots.repository.CategoryRepository;
import in.BidPilots.repository.MatchedBidsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserSavedFilterService {

    private final UserSavedFilterRepository savedFilterRepository;
    private final UserBidMatchingService    userBidMatchingService;
    private final LocationDemandService     locationDemandService;   // NEW
    private final CategoryRepository        categoryRepository;
    private final MatchedBidsRepository     matchedBidsRepository;
    private final ObjectMapper              objectMapper = new ObjectMapper();

    // ── Filter type constants ─────────────────────────────────────────────────
    private static final String TYPE_BOQ      = "BOQ";
    private static final String TYPE_LOCATION = "LOCATION";

    /**
     * Save a new filter for the authenticated user.
     *
     * Supported filter types:
     *
     * 1. SMART / EXACT / BROAD  — Category + State/City filter.
     *    Requires: categoryId + at least one stateId.
     *    Matching: done via BidDetails.itemCategory / Bid.items / Bid.dataContent
     *              according to the 3-tier cascade in UserBidMatchingService.
     *
     * 2. BOQ — BOQ title search inside the stored PDF content.
     *    Requires: boqTitle (keyword/phrase to look for).
     *    Optional: stateIds, cityIds for geographic narrowing.
     *    Matching: case-insensitive substring search in BidDetails.pdfContent,
     *              with fallback to itemCategory / items / dataContent.
     *
     * 3. LOCATION — State + City only (no category needed).
     *    Requires: at least one stateId.
     *    Optional: cityIds.
     *    Matches ALL active bids in the selected location.
     *
     * NEW: After saving the filter, all selected states and cities are marked
     * is_demanded = true in the DB. GeMScrapingService will then include those
     * locations in its next scrape cycle, so users only wait one cycle before
     * bids from their chosen locations start appearing.
     */
    @Transactional
    public Map<String, Object> saveFilter(Long userId, SaveFilterRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Saving filter for user={} name='{}' type={}",
                    userId, request.getFilterName(), request.getFilterType());

            String filterType = (request.getFilterType() != null)
                    ? request.getFilterType().toUpperCase()
                    : "SMART";

            // ── Type-specific validation ──────────────────────────────────────
            String validationError = validateByType(filterType, request);
            if (validationError != null) {
                response.put("success", false);
                response.put("message", validationError);
                return response;
            }

            // ── Duplicate name check ──────────────────────────────────────────
            if (savedFilterRepository.existsByUserIdAndFilterName(userId, request.getFilterName())) {
                response.put("success", false);
                response.put("message", "A filter with this name already exists");
                return response;
            }

            // ── Build entity ──────────────────────────────────────────────────
            UserSavedFilter filter = new UserSavedFilter();
            filter.setUserId(userId);
            filter.setFilterName(request.getFilterName());
            filter.setFilterType(filterType);
            filter.setCategoryId(request.getCategoryId());

            if (request.getStateIds() != null && !request.getStateIds().isEmpty()) {
                filter.setStateIds(objectMapper.writeValueAsString(request.getStateIds()));
            }
            if (request.getCityIds() != null && !request.getCityIds().isEmpty()) {
                filter.setCityIds(objectMapper.writeValueAsString(request.getCityIds()));
            }

            filter.setBoqTitle(request.getBoqTitle());

            UserSavedFilter saved = savedFilterRepository.save(filter);
            log.info("Filter saved id={} type={}", saved.getId(), filterType);

            // ── Mark selected locations as demanded (NEW) ─────────────────────
            // This tells GeMScrapingService to scrape these states/cities in the
            // next cycle. Only states/cities referenced by at least one filter
            // will ever be scraped, keeping the total bid volume manageable.
            locationDemandService.markDemanded(saved);

            // ── Immediate async matching ──────────────────────────────────────
            userBidMatchingService.runMatchingForFilter(userId, saved);

            response.put("success", true);
            response.put("message", "Filter saved. Matching bids are being loaded.");
            response.put("filter", FilterResponseDTO.fromEntity(saved));

        } catch (Exception e) {
            log.error("Error saving filter for user={}: {}", userId, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to save filter: " + e.getMessage());
        }

        return response;
    }

    /**
     * Type-specific validation.
     * Returns an error message string, or null if valid.
     */
    private String validateByType(String filterType, SaveFilterRequest r) {
        switch (filterType) {
            case TYPE_BOQ:
                if (r.getBoqTitle() == null || r.getBoqTitle().isBlank())
                    return "BOQ title/keyword is required for a BOQ filter";
                break;

            case TYPE_LOCATION:
                if (r.getStateIds() == null || r.getStateIds().isEmpty())
                    return "At least one state is required for a Location filter";
                break;

            default: // SMART / EXACT / BROAD
                if (r.getCategoryId() == null)
                    return "Category is required";
                if (r.getStateIds() == null || r.getStateIds().isEmpty())
                    return "At least one state is required";
                break;
        }
        return null;
    }

    // ── Helper: enrich DTO with categoryName + matchCount ─────────────────────
    private FilterResponseDTO enrichDto(UserSavedFilter filter) {
        String categoryName = null;
        if (filter.getCategoryId() != null) {
            categoryName = categoryRepository.findById(filter.getCategoryId())
                    .map(c -> c.getCategoryName())
                    .orElse(null);
        }
        long matchCount = matchedBidsRepository.countByFilterId(filter.getId());
        return FilterResponseDTO.fromEntity(filter, categoryName, matchCount);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getUserFilters(Long userId) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<FilterResponseDTO> dtos = savedFilterRepository.findByUserId(userId)
                    .stream()
                    .map(this::enrichDto)
                    .collect(Collectors.toList());
            response.put("success", true);
            response.put("filters", dtos);
            response.put("total", dtos.size());
        } catch (Exception e) {
            log.error("Error fetching filters for user={}: {}", userId, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to fetch filters");
        }
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getFilterById(Long userId, Long filterId) {
        Map<String, Object> response = new HashMap<>();
        try {
            Optional<UserSavedFilter> filter =
                    savedFilterRepository.findByIdAndUserId(filterId, userId);
            if (filter.isEmpty()) {
                response.put("success", false);
                response.put("message", "Filter not found");
                return response;
            }
            response.put("success", true);
            response.put("filter", enrichDto(filter.get()));
        } catch (Exception e) {
            log.error("Error fetching filter {} for user={}: {}", filterId, userId, e.getMessage());
            response.put("success", false);
            response.put("message", "Failed to fetch filter");
        }
        return response;
    }

    @Transactional
    public Map<String, Object> deleteFilter(Long userId, Long filterId) {
        Map<String, Object> response = new HashMap<>();
        try {
            int deleted = savedFilterRepository.deleteByUserIdAndId(userId, filterId);
            if (deleted == 0) {
                response.put("success", false);
                response.put("message", "Filter not found or access denied");
                return response;
            }
            log.info("Filter {} deleted for user {}", filterId, userId);
            response.put("success", true);
            response.put("message", "Filter deleted successfully");
        } catch (Exception e) {
            log.error("Error deleting filter {} for user={}: {}", filterId, userId, e.getMessage());
            response.put("success", false);
            response.put("message", "Failed to delete filter");
        }
        return response;
    }

    @Transactional
    public Map<String, Object> updateFilterLastUsed(Long userId, Long filterId) {
        Map<String, Object> response = new HashMap<>();
        try {
            Optional<UserSavedFilter> opt =
                    savedFilterRepository.findByIdAndUserId(filterId, userId);
            if (opt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Filter not found");
                return response;
            }
            opt.get().setLastUsedAt(LocalDateTime.now());
            savedFilterRepository.save(opt.get());
            response.put("success", true);
        } catch (Exception e) {
            log.error("Error updating last-used for filter {}: {}", filterId, e.getMessage());
            response.put("success", false);
            response.put("message", "Failed to update filter");
        }
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getUserFiltersByType(Long userId, String filterType) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<FilterResponseDTO> dtos = savedFilterRepository
                    .findByUserIdAndFilterType(userId, filterType)
                    .stream()
                    .map(FilterResponseDTO::fromEntity)
                    .collect(Collectors.toList());
            response.put("success", true);
            response.put("filters", dtos);
            response.put("total", dtos.size());
        } catch (Exception e) {
            log.error("Error fetching {} filters for user={}: {}", filterType, userId, e.getMessage());
            response.put("success", false);
            response.put("message", "Failed to fetch filters");
        }
        return response;
    }
}