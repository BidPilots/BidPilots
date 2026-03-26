// File: AdvOrganizationSearchService.java
package in.BidPilots.service.AdvService;

import in.BidPilots.dto.AdvDTO.AdvOrganizationDTO;
import in.BidPilots.entity.AdvEntity.AdvMinistry;
import in.BidPilots.entity.AdvEntity.AdvOrganization;
import in.BidPilots.entity.AdvEntity.AdvState;
import in.BidPilots.repository.AdvRepository.AdvMinistryRepository;
import in.BidPilots.repository.AdvRepository.AdvOrganizationRepository;
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
public class AdvOrganizationSearchService {

    private final AdvOrganizationRepository advOrganizationRepository;
    private final AdvMinistryRepository advMinistryRepository;
    private final AdvStateRepository advStateRepository;

    @Transactional(readOnly = true)
    public List<AdvOrganizationDTO> getAllOrganizations() {
        return advOrganizationRepository.findAll()
                .stream()
                .map(AdvOrganizationDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AdvOrganizationDTO getOrganizationById(Long id) {
        return advOrganizationRepository.findById(id)
                .map(AdvOrganizationDTO::fromEntity)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<AdvOrganizationDTO> getOrganizationsByMinistry(Long ministryId) {
        return advOrganizationRepository.findByMinistryId(ministryId)
                .stream()
                .map(AdvOrganizationDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AdvOrganizationDTO> getOrganizationsByState(Long stateId) {
        return advOrganizationRepository.findByStateId(stateId)
                .stream()
                .map(AdvOrganizationDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AdvOrganizationDTO> getOrganizationsByMinistryAndState(Long ministryId, Long stateId) {
        return advOrganizationRepository.findByMinistryIdAndStateId(ministryId, stateId)
                .stream()
                .map(AdvOrganizationDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getOrganizationsWithFilters(Long ministryId, Long stateId) {
        Map<String, Object> response = new HashMap<>();
        List<AdvOrganization> organizations;

        if (ministryId != null && stateId != null) {
            organizations = advOrganizationRepository.findByMinistryIdAndStateId(ministryId, stateId);
        } else if (ministryId != null) {
            organizations = advOrganizationRepository.findByMinistryId(ministryId);
        } else if (stateId != null) {
            organizations = advOrganizationRepository.findByStateId(stateId);
        } else {
            organizations = advOrganizationRepository.findAll();
        }

        List<AdvOrganizationDTO> orgDTOs = organizations.stream()
                .map(AdvOrganizationDTO::fromEntity)
                .collect(Collectors.toList());

        response.put("success", true);
        response.put("total", organizations.size());
        response.put("organizations", orgDTOs);
        
        if (ministryId != null) {
            advMinistryRepository.findById(ministryId)
                .ifPresent(m -> response.put("ministryName", m.getMinistryName()));
        }
        
        if (stateId != null) {
            advStateRepository.findById(stateId)
                .ifPresent(s -> response.put("stateName", s.getStateName()));
        }

        return response;
    }
}