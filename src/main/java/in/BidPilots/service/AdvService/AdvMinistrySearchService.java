// File: AdvMinistrySearchService.java
package in.BidPilots.service.AdvService;

import in.BidPilots.dto.AdvDTO.AdvMinistryDTO;
import in.BidPilots.entity.AdvEntity.AdvMinistry;
import in.BidPilots.repository.AdvRepository.AdvMinistryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdvMinistrySearchService {

    private final AdvMinistryRepository advMinistryRepository;

    @Transactional(readOnly = true)
    public List<AdvMinistryDTO> getAllMinistries() {
        return advMinistryRepository.findAllOrderByName()
                .stream()
                .map(AdvMinistryDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AdvMinistryDTO getMinistryById(Long id) {
        return advMinistryRepository.findById(id)
                .map(AdvMinistryDTO::fromEntity)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public AdvMinistryDTO getMinistryByName(String name) {
        return advMinistryRepository.findByMinistryName(name)
                .map(AdvMinistryDTO::fromEntity)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<AdvMinistryDTO> searchMinistries(String keyword) {
        return advMinistryRepository.searchByMinistryName(keyword)
                .stream()
                .map(AdvMinistryDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getMinistriesWithStats() {
        Map<String, Object> response = new HashMap<>();
        List<AdvMinistry> ministries = advMinistryRepository.findAll();
        
        List<Map<String, Object>> ministryList = ministries.stream().map(m -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", m.getId());
            map.put("name", m.getMinistryName());
            map.put("organizationCount", m.getAdvOrganizations() != null ? m.getAdvOrganizations().size() : 0);
            return map;
        }).collect(Collectors.toList());

        response.put("success", true);
        response.put("total", ministries.size());
        response.put("ministries", ministryList);
        return response;
    }
}