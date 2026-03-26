// File: AdvDepartmentDTO.java
package in.BidPilots.dto.AdvDTO;

import in.BidPilots.entity.AdvEntity.AdvDepartment;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdvDepartmentDTO {
    private Long id;
    private String departmentName;
    private Long organizationId;
    private String organizationName;
    private Long ministryId;
    private String ministryName;
    private Long stateId;
    private String stateName;

    public static AdvDepartmentDTO fromEntity(AdvDepartment department) {
        if (department == null) return null;
        
        AdvDepartmentDTO dto = new AdvDepartmentDTO();
        dto.setId(department.getId());
        dto.setDepartmentName(department.getDepartmentName());
        
        if (department.getAdvOrganization() != null) {
            dto.setOrganizationId(department.getAdvOrganization().getId());
            dto.setOrganizationName(department.getAdvOrganization().getOrganizationName());
        }
        
        if (department.getAdvMinistry() != null) {
            dto.setMinistryId(department.getAdvMinistry().getId());
            dto.setMinistryName(department.getAdvMinistry().getMinistryName());
        }
        
        if (department.getAdvState() != null) {
            dto.setStateId(department.getAdvState().getId());
            dto.setStateName(department.getAdvState().getStateName());
        }
        
        return dto;
    }
}