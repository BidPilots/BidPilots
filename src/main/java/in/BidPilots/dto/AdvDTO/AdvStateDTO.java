// File: AdvStateDTO.java
package in.BidPilots.dto.AdvDTO;

import in.BidPilots.entity.AdvEntity.AdvState;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdvStateDTO {
    private Long id;
    private String stateName;

    public static AdvStateDTO fromEntity(AdvState state) {
        if (state == null) return null;
        return new AdvStateDTO(state.getId(), state.getStateName());
    }
}