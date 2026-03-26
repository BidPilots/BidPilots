// File: AdvDepartment.java
package in.BidPilots.entity.AdvEntity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Entity
@Table(name = "adv_departments",
       uniqueConstraints = @UniqueConstraint(columnNames = {"department_name", "organization_id"}, 
                                            name = "uk_adv_dept_name_org"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AdvDepartment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "department_name", nullable = false, length = 500)
    private String departmentName;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "organization_id", nullable = false)
    @JsonIgnoreProperties({"advDepartments", "advMinistry", "advState"})
    private AdvOrganization advOrganization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ministry_id")
    @JsonIgnoreProperties({"advOrganizations", "advDepartments"})
    private AdvMinistry advMinistry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "state_id")
    @JsonIgnoreProperties({"advOrganizations", "advDepartments"})
    private AdvState advState;
}