// File: AdvDepartmentSearchService.java
package in.BidPilots.service.AdvService;

import in.BidPilots.dto.AdvDTO.AdvDepartmentDTO;
import in.BidPilots.entity.AdvEntity.AdvDepartment;
import in.BidPilots.entity.AdvEntity.AdvMinistry;
import in.BidPilots.entity.AdvEntity.AdvOrganization;
import in.BidPilots.entity.AdvEntity.AdvState;
import in.BidPilots.repository.AdvRepository.AdvDepartmentRepository;
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
public class AdvDepartmentSearchService {

    private final AdvDepartmentRepository advDepartmentRepository;
    private final AdvOrganizationRepository advOrganizationRepository;
    private final AdvMinistryRepository advMinistryRepository;
    private final AdvStateRepository advStateRepository;

    @Transactional(readOnly = true)
    public List<AdvDepartmentDTO> getAllDepartments() {
        return advDepartmentRepository.findAll()
                .stream()
                .map(AdvDepartmentDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AdvDepartmentDTO getDepartmentById(Long id) {
        return advDepartmentRepository.findById(id)
                .map(AdvDepartmentDTO::fromEntity)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<AdvDepartmentDTO> getDepartmentsByOrganization(Long organizationId) {
        return advDepartmentRepository.findByOrganizationId(organizationId)
                .stream()
                .map(AdvDepartmentDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AdvDepartmentDTO> getDepartmentsByMinistry(Long ministryId) {
        return advDepartmentRepository.findByMinistryId(ministryId)
                .stream()
                .map(AdvDepartmentDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AdvDepartmentDTO> getDepartmentsByState(Long stateId) {
        return advDepartmentRepository.findByStateId(stateId)
                .stream()
                .map(AdvDepartmentDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDepartmentsWithFilters(Long ministryId, Long stateId, Long organizationId) {
        Map<String, Object> response = new HashMap<>();
        List<AdvDepartment> departments;

        if (organizationId != null) {
            departments = advDepartmentRepository.findByOrganizationId(organizationId);
        } else if (ministryId != null) {
            departments = advDepartmentRepository.findByMinistryId(ministryId);
        } else if (stateId != null) {
            departments = advDepartmentRepository.findByStateId(stateId);
        } else {
            departments = advDepartmentRepository.findAll();
        }

        List<AdvDepartmentDTO> deptDTOs = departments.stream()
                .map(AdvDepartmentDTO::fromEntity)
                .collect(Collectors.toList());

        response.put("success", true);
        response.put("total", departments.size());
        response.put("departments", deptDTOs);
        
        if (organizationId != null) {
            advOrganizationRepository.findById(organizationId)
                .ifPresent(o -> response.put("organizationName", o.getOrganizationName()));
        }
        
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