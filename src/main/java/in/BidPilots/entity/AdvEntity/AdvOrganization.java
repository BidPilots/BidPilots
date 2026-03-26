// File: AdvOrganization.java
package in.BidPilots.entity.AdvEntity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@Entity
@Table(name = "adv_organizations",
       uniqueConstraints = @UniqueConstraint(columnNames = {"organization_name", "ministry_id", "state_id"}, 
                                            name = "uk_adv_org_name_ministry_state"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "advDepartments"})
public class AdvOrganization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_name", nullable = false, length = 500)
    private String organizationName;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "ministry_id")
    @JsonIgnoreProperties({"advOrganizations", "advDepartments"})
    private AdvMinistry advMinistry;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "state_id")
    @JsonIgnoreProperties({"advOrganizations", "advDepartments"})
    private AdvState advState;

    @OneToMany(mappedBy = "advOrganization", fetch = FetchType.LAZY)
    @JsonIgnoreProperties("advOrganization")
    private List<AdvDepartment> advDepartments;
}