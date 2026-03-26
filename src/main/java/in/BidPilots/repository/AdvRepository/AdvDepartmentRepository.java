// File: AdvDepartmentRepository.java
package in.BidPilots.repository.AdvRepository;

import in.BidPilots.entity.AdvEntity.AdvDepartment;
import in.BidPilots.entity.AdvEntity.AdvOrganization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdvDepartmentRepository extends JpaRepository<AdvDepartment, Long> {

    Optional<AdvDepartment> findByDepartmentNameAndAdvOrganization(String departmentName, AdvOrganization advOrganization);

    @Query("SELECT d FROM AdvDepartment d WHERE d.advOrganization.id = :organizationId ORDER BY d.departmentName")
    List<AdvDepartment> findByOrganizationId(@Param("organizationId") Long organizationId);

    @Query("SELECT d FROM AdvDepartment d WHERE d.advMinistry.id = :ministryId ORDER BY d.departmentName")
    List<AdvDepartment> findByMinistryId(@Param("ministryId") Long ministryId);

    @Query("SELECT d FROM AdvDepartment d WHERE d.advState.id = :stateId ORDER BY d.departmentName")
    List<AdvDepartment> findByStateId(@Param("stateId") Long stateId);

    @Query("SELECT COUNT(d) FROM AdvDepartment d WHERE d.advOrganization.id = :organizationId")
    long countByOrganizationId(@Param("organizationId") Long organizationId);

    boolean existsByDepartmentName(String departmentName);
}