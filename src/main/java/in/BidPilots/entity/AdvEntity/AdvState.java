// File: AdvState.java
package in.BidPilots.entity.AdvEntity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@Entity
@Table(name = "adv_states", 
       uniqueConstraints = @UniqueConstraint(columnNames = "state_name", name = "uk_adv_state_name"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "advOrganizations"})
public class AdvState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "state_name", nullable = false, length = 100, unique = true)
    private String stateName;

    @OneToMany(mappedBy = "advState", fetch = FetchType.LAZY)
    @JsonIgnoreProperties("advState")
    private List<AdvOrganization> advOrganizations;
}