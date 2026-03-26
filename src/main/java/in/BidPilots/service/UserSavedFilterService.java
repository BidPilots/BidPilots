package in.BidPilots.service;

import in.BidPilots.dto.FilterResponseDTO;
import in.BidPilots.dto.SaveFilterRequest;
import in.BidPilots.entity.UserSavedFilter;
import in.BidPilots.repository.UserSavedFilterRepository;
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
    private final UserBidMatchingService userBidMatchingService; // FIX: injected for immediate matching
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Save a filter for the given user (userId comes from the authenticated principal,
     * NOT from the request body — prevents IDOR).
     *
     * KEY FIX: After saving, immediately triggers async matching so that results
     * appear on the page right away instead of waiting up to 15 minutes.
     */
    @Transactional
    public Map<String, Object> saveFilter(Long userId, SaveFilterRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Saving filter for user: {}, name: {}", userId, request.getFilterName());

            // Check for duplicate filter name for this user
            if (savedFilterRepository.existsByUserIdAndFilterName(userId, request.getFilterName())) {
                response.put("success", false);
                response.put("message", "A filter with this name already exists");
                return response;
            }

            UserSavedFilter filter = new UserSavedFilter();
            filter.setUserId(userId);
            filter.setFilterName(request.getFilterName());
            filter.setFilterType(request.getFilterType() != null ? request.getFilterType() : "CUSTOM");
            filter.setCategoryId(request.getCategoryId());

            // Convert stateIds list to JSON string
            if (request.getStateIds() != null && !request.getStateIds().isEmpty()) {
                filter.setStateIds(objectMapper.writeValueAsString(request.getStateIds()));
            }

            // Convert cityIds list to JSON string
            if (request.getCityIds() != null && !request.getCityIds().isEmpty()) {
                filter.setCityIds(objectMapper.writeValueAsString(request.getCityIds()));
            }

            UserSavedFilter savedFilter = savedFilterRepository.save(filter);
            log.info("✅ Filter saved with ID: {}", savedFilter.getId());

            // =========================================================
            // FIX: IMMEDIATE MATCHING — run async matching for this
            // specific filter right now so results show up immediately.
            // The scheduled job every 15 min still runs for all filters,
            // but this ensures new filter results are instant.
            // =========================================================
            userBidMatchingService.runMatchingForFilter(userId, savedFilter);

            response.put("success", true);
            response.put("message", "Filter saved successfully. Matching bids are being loaded.");
            response.put("filter", FilterResponseDTO.fromEntity(savedFilter));

        } catch (Exception e) {
            log.error("❌ Error saving filter: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to save filter: " + e.getMessage());
        }

        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getUserFilters(Long userId) {
        Map<String, Object> response = new HashMap<>();

        try {
            List<FilterResponseDTO> filterDTOs = savedFilterRepository.findByUserId(userId)
                    .stream()
                    .map(FilterResponseDTO::fromEntity)
                    .collect(Collectors.toList());

            response.put("success", true);
            response.put("filters", filterDTOs);
            response.put("total", filterDTOs.size());

        } catch (Exception e) {
            log.error("Error fetching user filters: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to fetch filters: " + e.getMessage());
        }

        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getFilterById(Long userId, Long filterId) {
        Map<String, Object> response = new HashMap<>();

        try {
            // FIX: Verify ownership — original code fetched by ID only, any user could
            // read any filter by guessing IDs (IDOR).
            Optional<UserSavedFilter> filter = savedFilterRepository.findByIdAndUserId(filterId, userId);

            if (filter.isEmpty()) {
                response.put("success", false);
                response.put("message", "Filter not found");
                return response;
            }

            response.put("success", true);
            response.put("filter", FilterResponseDTO.fromEntity(filter.get()));

        } catch (Exception e) {
            log.error("Error fetching filter: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to fetch filter: " + e.getMessage());
        }

        return response;
    }

    @Transactional
    public Map<String, Object> deleteFilter(Long userId, Long filterId) {
        Map<String, Object> response = new HashMap<>();

        try {
            // FIX: Replaced TOCTOU pattern (existsById → deleteByUserIdAndId) with a
            // single atomic delete. The old code had a race window between check and delete.
            int deleted = savedFilterRepository.deleteByUserIdAndId(userId, filterId);

            if (deleted == 0) {
                response.put("success", false);
                response.put("message", "Filter not found or access denied");
                return response;
            }

            log.info("✅ Filter {} deleted for user {}", filterId, userId);
            response.put("success", true);
            response.put("message", "Filter deleted successfully");

        } catch (Exception e) {
            log.error("Error deleting filter: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to delete filter: " + e.getMessage());
        }

        return response;
    }

    @Transactional
    public Map<String, Object> updateFilterLastUsed(Long userId, Long filterId) {
        Map<String, Object> response = new HashMap<>();

        try {
            // FIX: Verify ownership before updating
            Optional<UserSavedFilter> optFilter = savedFilterRepository.findByIdAndUserId(filterId, userId);

            if (optFilter.isEmpty()) {
                response.put("success", false);
                response.put("message", "Filter not found");
                return response;
            }

            UserSavedFilter filter = optFilter.get();
            filter.setLastUsedAt(LocalDateTime.now());
            savedFilterRepository.save(filter);

            response.put("success", true);

        } catch (Exception e) {
            log.error("Error updating last used: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to update filter");
        }

        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getUserFiltersByType(Long userId, String filterType) {
        Map<String, Object> response = new HashMap<>();

        try {
            List<FilterResponseDTO> filterDTOs = savedFilterRepository
                    .findByUserIdAndFilterType(userId, filterType)
                    .stream()
                    .map(FilterResponseDTO::fromEntity)
                    .collect(Collectors.toList());

            response.put("success", true);
            response.put("filters", filterDTOs);
            response.put("total", filterDTOs.size());

        } catch (Exception e) {
            log.error("Error fetching filters by type: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to fetch filters: " + e.getMessage());
        }

        return response;
    }
}