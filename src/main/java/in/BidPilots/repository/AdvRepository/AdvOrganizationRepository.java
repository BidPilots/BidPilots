// File: AdvOrganizationRepository.java
package in.BidPilots.repository.AdvRepository;

import in.BidPilots.entity.AdvEntity.AdvMinistry;
import in.BidPilots.entity.AdvEntity.AdvOrganization;
import in.BidPilots.entity.AdvEntity.AdvState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdvOrganizationRepository extends JpaRepository<AdvOrganization, Long> {

    Optional<AdvOrganization> findByOrganizationNameAndAdvMinistry(String organizationName, AdvMinistry advMinistry);

    Optional<AdvOrganization> findByOrganizationNameAndAdvState(String organizationName, AdvState advState);

    @Query("SELECT o FROM AdvOrganization o WHERE o.advMinistry.id = :ministryId ORDER BY o.organizationName")
    List<AdvOrganization> findByMinistryId(@Param("ministryId") Long ministryId);

    @Query("SELECT o FROM AdvOrganization o WHERE o.advState.id = :stateId ORDER BY o.organizationName")
    List<AdvOrganization> findByStateId(@Param("stateId") Long stateId);

    @Query("SELECT o FROM AdvOrganization o WHERE o.advMinistry.id = :ministryId AND o.advState.id = :stateId ORDER BY o.organizationName")
    List<AdvOrganization> findByMinistryIdAndStateId(@Param("ministryId") Long ministryId, @Param("stateId") Long stateId);

    @Query("SELECT COUNT(o) FROM AdvOrganization o WHERE o.advMinistry.id = :ministryId")
    long countByMinistryId(@Param("ministryId") Long ministryId);

    @Query("SELECT COUNT(o) FROM AdvOrganization o WHERE o.advState.id = :stateId")
    long countByStateId(@Param("stateId") Long stateId);

    boolean existsByOrganizationName(String organizationName);
}