// File: AdvSearchRequestDTO.java
package in.BidPilots.dto.AdvDTO;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdvSearchRequestDTO {
    private Long ministryId;
    private Long stateId;
    private Long organizationId;
    private String keyword;
    private Integer page;
    private Integer size;
}