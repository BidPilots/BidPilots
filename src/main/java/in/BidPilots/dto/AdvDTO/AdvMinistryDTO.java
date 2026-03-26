// File: AdvMinistryDTO.java
package in.BidPilots.dto.AdvDTO;

import in.BidPilots.entity.AdvEntity.AdvMinistry;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdvMinistryDTO {
    private Long id;
    private String ministryName;

    public static AdvMinistryDTO fromEntity(AdvMinistry ministry) {
        if (ministry == null) return null;
        return new AdvMinistryDTO(ministry.getId(), ministry.getMinistryName());
    }
}