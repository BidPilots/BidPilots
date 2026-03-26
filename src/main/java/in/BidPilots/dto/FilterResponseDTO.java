package in.BidPilots.dto;

import in.BidPilots.entity.UserSavedFilter;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class FilterResponseDTO {
    private Long id;
    private Long userId;
    private String filterName;
    private String filterType;
    private List<Long> stateIds = new ArrayList<>();
    private List<Long> cityIds = new ArrayList<>();
    private Long categoryId;
    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static FilterResponseDTO fromEntity(UserSavedFilter filter) {
        FilterResponseDTO dto = new FilterResponseDTO();
        dto.setId(filter.getId());
        dto.setUserId(filter.getUserId());
        dto.setFilterName(filter.getFilterName());
        dto.setFilterType(filter.getFilterType());
        dto.setCategoryId(filter.getCategoryId());
        dto.setCreatedAt(filter.getCreatedAt());
        dto.setLastUsedAt(filter.getLastUsedAt());

        // Parse stateIds from JSON
        if (filter.getStateIds() != null && !filter.getStateIds().isBlank()) {
            try {
                List<Long> states = objectMapper.readValue(filter.getStateIds(),
                        new TypeReference<List<Long>>() {});
                dto.setStateIds(states != null ? states : new ArrayList<>());
            } catch (Exception e) {
                log.warn("Failed to parse stateIds JSON for filter {}: {}", filter.getId(), e.getMessage());
                dto.setStateIds(new ArrayList<>());
            }
        }

        // Parse cityIds from JSON
        if (filter.getCityIds() != null && !filter.getCityIds().isBlank()) {
            try {
                List<Long> cities = objectMapper.readValue(filter.getCityIds(),
                        new TypeReference<List<Long>>() {});
                dto.setCityIds(cities != null ? cities : new ArrayList<>());
            } catch (Exception e) {
                log.warn("Failed to parse cityIds JSON for filter {}: {}", filter.getId(), e.getMessage());
                dto.setCityIds(new ArrayList<>());
            }
        }

        return dto;
    }
}