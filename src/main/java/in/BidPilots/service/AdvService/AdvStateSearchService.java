// File: AdvStateSearchService.java
package in.BidPilots.service.AdvService;

import in.BidPilots.dto.AdvDTO.AdvStateDTO;
import in.BidPilots.entity.AdvEntity.AdvState;
import in.BidPilots.repository.AdvRepository.AdvStateRepository;
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
public class AdvStateSearchService {

    private final AdvStateRepository advStateRepository;

    @Transactional(readOnly = true)
    public List<AdvStateDTO> getAllStates() {
        return advStateRepository.findAllOrderByName()
                .stream()
                .map(AdvStateDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AdvStateDTO getStateById(Long id) {
        return advStateRepository.findById(id)
                .map(AdvStateDTO::fromEntity)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public AdvStateDTO getStateByName(String name) {
        return advStateRepository.findByStateName(name)
                .map(AdvStateDTO::fromEntity)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<AdvStateDTO> searchStates(String keyword) {
        return advStateRepository.searchByStateName(keyword)
                .stream()
                .map(AdvStateDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStatesWithStats() {
        Map<String, Object> response = new HashMap<>();
        List<AdvState> states = advStateRepository.findAll();
        
        List<Map<String, Object>> stateList = states.stream().map(s -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", s.getId());
            map.put("name", s.getStateName());
            map.put("organizationCount", s.getAdvOrganizations() != null ? s.getAdvOrganizations().size() : 0);
            return map;
        }).collect(Collectors.toList());

        response.put("success", true);
        response.put("total", states.size());
        response.put("states", stateList);
        return response;
    }
}