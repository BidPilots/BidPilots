// File: AdvMinistryRepository.java
package in.BidPilots.repository.AdvRepository;

import in.BidPilots.entity.AdvEntity.AdvMinistry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdvMinistryRepository extends JpaRepository<AdvMinistry, Long> {

    Optional<AdvMinistry> findByMinistryName(String ministryName);

    boolean existsByMinistryName(String ministryName);

    @Query("SELECT m FROM AdvMinistry m ORDER BY m.ministryName")
    List<AdvMinistry> findAllOrderByName();

    @Query("SELECT m FROM AdvMinistry m WHERE LOWER(m.ministryName) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<AdvMinistry> searchByMinistryName(@Param("keyword") String keyword);
}