// File: AdvMinistry.java
package in.BidPilots.entity.AdvEntity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@Entity
@Table(name = "adv_ministries", 
       uniqueConstraints = @UniqueConstraint(columnNames = "ministry_name", name = "uk_adv_ministry_name"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "advOrganizations"})
public class AdvMinistry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ministry_name", nullable = false, length = 255, unique = true)
    private String ministryName;

    @OneToMany(mappedBy = "advMinistry", fetch = FetchType.LAZY)
    @JsonIgnoreProperties("advMinistry")
    private List<AdvOrganization> advOrganizations;
}