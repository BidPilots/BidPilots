// File: AdvSearchResponseDTO.java
package in.BidPilots.dto.AdvDTO;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdvSearchResponseDTO {
    private List<AdvMinistryDTO> ministries;
    private List<AdvStateDTO> states;
    private List<AdvOrganizationDTO> organizations;
    private List<AdvDepartmentDTO> departments;
    private Long totalMinistries;
    private Long totalStates;
    private Long totalOrganizations;
    private Long totalDepartments;
    private Integer page;
    private Integer size;
    private Boolean success;
    private String message;
}