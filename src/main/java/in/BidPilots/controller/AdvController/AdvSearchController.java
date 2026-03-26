// File: AdvSearchController.java
package in.BidPilots.controller.AdvController;

import in.BidPilots.dto.AdvDTO.*;
import in.BidPilots.service.AdvService.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/advance-search/data")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class AdvSearchController {

    private final AdvMinistrySearchService advMinistrySearchService;
    private final AdvStateSearchService advStateSearchService;
    private final AdvOrganizationSearchService advOrganizationSearchService;
    private final AdvDepartmentSearchService advDepartmentSearchService;

    // ==================== MINISTRY ENDPOINTS ====================

    @GetMapping("/ministries")
    public ResponseEntity<Map<String, Object>> getAllMinistries() {
        Map<String, Object> response = new HashMap<>();
        List<AdvMinistryDTO> ministries = advMinistrySearchService.getAllMinistries();
        
        response.put("success", true);
        response.put("total", ministries.size());
        response.put("ministries", ministries);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/ministries/{id}")
    public ResponseEntity<Map<String, Object>> getMinistryById(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        AdvMinistryDTO ministry = advMinistrySearchService.getMinistryById(id);
        
        if (ministry == null) {
            response.put("success", false);
            response.put("message", "Ministry not found");
            return ResponseEntity.status(404).body(response);
        }
        
        response.put("success", true);
        response.put("ministry", ministry);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/ministries/search")
    public ResponseEntity<Map<String, Object>> searchMinistries(@RequestParam String keyword) {
        Map<String, Object> response = new HashMap<>();
        List<AdvMinistryDTO> ministries = advMinistrySearchService.searchMinistries(keyword);
        
        response.put("success", true);
        response.put("total", ministries.size());
        response.put("ministries", ministries);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/ministries/with-stats")
    public ResponseEntity<Map<String, Object>> getMinistriesWithStats() {
        return ResponseEntity.ok(advMinistrySearchService.getMinistriesWithStats());
    }

    // ==================== STATE ENDPOINTS ====================

    @GetMapping("/states")
    public ResponseEntity<Map<String, Object>> getAllStates() {
        Map<String, Object> response = new HashMap<>();
        List<AdvStateDTO> states = advStateSearchService.getAllStates();
        
        response.put("success", true);
        response.put("total", states.size());
        response.put("states", states);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/states/{id}")
    public ResponseEntity<Map<String, Object>> getStateById(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        AdvStateDTO state = advStateSearchService.getStateById(id);
        
        if (state == null) {
            response.put("success", false);
            response.put("message", "State not found");
            return ResponseEntity.status(404).body(response);
        }
        
        response.put("success", true);
        response.put("state", state);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/states/search")
    public ResponseEntity<Map<String, Object>> searchStates(@RequestParam String keyword) {
        Map<String, Object> response = new HashMap<>();
        List<AdvStateDTO> states = advStateSearchService.searchStates(keyword);
        
        response.put("success", true);
        response.put("total", states.size());
        response.put("states", states);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/states/with-stats")
    public ResponseEntity<Map<String, Object>> getStatesWithStats() {
        return ResponseEntity.ok(advStateSearchService.getStatesWithStats());
    }

    // ==================== ORGANIZATION ENDPOINTS ====================

    @GetMapping("/organizations")
    public ResponseEntity<Map<String, Object>> getAllOrganizations() {
        Map<String, Object> response = new HashMap<>();
        List<AdvOrganizationDTO> organizations = advOrganizationSearchService.getAllOrganizations();
        
        response.put("success", true);
        response.put("total", organizations.size());
        response.put("organizations", organizations);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/organizations/{id}")
    public ResponseEntity<Map<String, Object>> getOrganizationById(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        AdvOrganizationDTO organization = advOrganizationSearchService.getOrganizationById(id);
        
        if (organization == null) {
            response.put("success", false);
            response.put("message", "Organization not found");
            return ResponseEntity.status(404).body(response);
        }
        
        response.put("success", true);
        response.put("organization", organization);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/organizations/by-ministry/{ministryId}")
    public ResponseEntity<Map<String, Object>> getOrganizationsByMinistry(@PathVariable Long ministryId) {
        Map<String, Object> response = new HashMap<>();
        List<AdvOrganizationDTO> organizations = advOrganizationSearchService.getOrganizationsByMinistry(ministryId);
        
        response.put("success", true);
        response.put("total", organizations.size());
        response.put("organizations", organizations);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/organizations/by-state/{stateId}")
    public ResponseEntity<Map<String, Object>> getOrganizationsByState(@PathVariable Long stateId) {
        Map<String, Object> response = new HashMap<>();
        List<AdvOrganizationDTO> organizations = advOrganizationSearchService.getOrganizationsByState(stateId);
        
        response.put("success", true);
        response.put("total", organizations.size());
        response.put("organizations", organizations);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/organizations/filter")
    public ResponseEntity<Map<String, Object>> getOrganizationsByFilters(
            @RequestParam(required = false) Long ministryId,
            @RequestParam(required = false) Long stateId) {
        
        return ResponseEntity.ok(advOrganizationSearchService.getOrganizationsWithFilters(ministryId, stateId));
    }

    // ==================== DEPARTMENT ENDPOINTS ====================

    @GetMapping("/departments")
    public ResponseEntity<Map<String, Object>> getAllDepartments() {
        Map<String, Object> response = new HashMap<>();
        List<AdvDepartmentDTO> departments = advDepartmentSearchService.getAllDepartments();
        
        response.put("success", true);
        response.put("total", departments.size());
        response.put("departments", departments);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/departments/{id}")
    public ResponseEntity<Map<String, Object>> getDepartmentById(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        AdvDepartmentDTO department = advDepartmentSearchService.getDepartmentById(id);
        
        if (department == null) {
            response.put("success", false);
            response.put("message", "Department not found");
            return ResponseEntity.status(404).body(response);
        }
        
        response.put("success", true);
        response.put("department", department);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/departments/by-organization/{organizationId}")
    public ResponseEntity<Map<String, Object>> getDepartmentsByOrganization(@PathVariable Long organizationId) {
        Map<String, Object> response = new HashMap<>();
        List<AdvDepartmentDTO> departments = advDepartmentSearchService.getDepartmentsByOrganization(organizationId);
        
        response.put("success", true);
        response.put("total", departments.size());
        response.put("departments", departments);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/departments/by-ministry/{ministryId}")
    public ResponseEntity<Map<String, Object>> getDepartmentsByMinistry(@PathVariable Long ministryId) {
        Map<String, Object> response = new HashMap<>();
        List<AdvDepartmentDTO> departments = advDepartmentSearchService.getDepartmentsByMinistry(ministryId);
        
        response.put("success", true);
        response.put("total", departments.size());
        response.put("departments", departments);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/departments/by-state/{stateId}")
    public ResponseEntity<Map<String, Object>> getDepartmentsByState(@PathVariable Long stateId) {
        Map<String, Object> response = new HashMap<>();
        List<AdvDepartmentDTO> departments = advDepartmentSearchService.getDepartmentsByState(stateId);
        
        response.put("success", true);
        response.put("total", departments.size());
        response.put("departments", departments);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/departments/filter")
    public ResponseEntity<Map<String, Object>> getDepartmentsWithFilters(
            @RequestParam(required = false) Long ministryId,
            @RequestParam(required = false) Long stateId,
            @RequestParam(required = false) Long organizationId) {
        
        return ResponseEntity.ok(advDepartmentSearchService.getDepartmentsWithFilters(ministryId, stateId, organizationId));
    }

    // ==================== HIERARCHY ENDPOINTS ====================

    @GetMapping("/hierarchy/ministry/{ministryId}")
    public ResponseEntity<Map<String, Object>> getCompleteHierarchyByMinistry(@PathVariable Long ministryId) {
        Map<String, Object> response = new HashMap<>();
        
        AdvMinistryDTO ministry = advMinistrySearchService.getMinistryById(ministryId);
        if (ministry == null) {
            response.put("success", false);
            response.put("message", "Ministry not found");
            return ResponseEntity.status(404).body(response);
        }
        
        List<AdvOrganizationDTO> organizations = advOrganizationSearchService.getOrganizationsByMinistry(ministryId);
        List<AdvDepartmentDTO> departments = advDepartmentSearchService.getDepartmentsByMinistry(ministryId);
        
        response.put("success", true);
        response.put("ministry", ministry);
        response.put("organizations", organizations);
        response.put("organizationsCount", organizations.size());
        response.put("departments", departments);
        response.put("departmentsCount", departments.size());
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/hierarchy/state/{stateId}")
    public ResponseEntity<Map<String, Object>> getCompleteHierarchyByState(@PathVariable Long stateId) {
        Map<String, Object> response = new HashMap<>();
        
        AdvStateDTO state = advStateSearchService.getStateById(stateId);
        if (state == null) {
            response.put("success", false);
            response.put("message", "State not found");
            return ResponseEntity.status(404).body(response);
        }
        
        List<AdvOrganizationDTO> organizations = advOrganizationSearchService.getOrganizationsByState(stateId);
        List<AdvDepartmentDTO> departments = advDepartmentSearchService.getDepartmentsByState(stateId);
        
        response.put("success", true);
        response.put("state", state);
        response.put("organizations", organizations);
        response.put("organizationsCount", organizations.size());
        response.put("departments", departments);
        response.put("departmentsCount", departments.size());
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/hierarchy/organization/{organizationId}")
    public ResponseEntity<Map<String, Object>> getCompleteHierarchyByOrganization(@PathVariable Long organizationId) {
        Map<String, Object> response = new HashMap<>();
        
        AdvOrganizationDTO organization = advOrganizationSearchService.getOrganizationById(organizationId);
        if (organization == null) {
            response.put("success", false);
            response.put("message", "Organization not found");
            return ResponseEntity.status(404).body(response);
        }
        
        List<AdvDepartmentDTO> departments = advDepartmentSearchService.getDepartmentsByOrganization(organizationId);
        
        response.put("success", true);
        response.put("organization", organization);
        response.put("departments", departments);
        response.put("departmentsCount", departments.size());
        
        return ResponseEntity.ok(response);
    }
}