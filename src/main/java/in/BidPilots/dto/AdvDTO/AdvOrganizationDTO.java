// File: AdvOrganizationDTO.java
package in.BidPilots.dto.AdvDTO;

import in.BidPilots.entity.AdvEntity.AdvOrganization;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdvOrganizationDTO {
    private Long id;
    private String organizationName;
    private Long ministryId;
    private String ministryName;
    private Long stateId;
    private String stateName;

    public static AdvOrganizationDTO fromEntity(AdvOrganization organization) {
        if (organization == null) return null;
        
        AdvOrganizationDTO dto = new AdvOrganizationDTO();
        dto.setId(organization.getId());
        dto.setOrganizationName(organization.getOrganizationName());
        
        if (organization.getAdvMinistry() != null) {
            dto.setMinistryId(organization.getAdvMinistry().getId());
            dto.setMinistryName(organization.getAdvMinistry().getMinistryName());
        }
        
        if (organization.getAdvState() != null) {
            dto.setStateId(organization.getAdvState().getId());
            dto.setStateName(organization.getAdvState().getStateName());
        }
        
        return dto;
    }
}