// File: AdvStateRepository.java
package in.BidPilots.repository.AdvRepository;

import in.BidPilots.entity.AdvEntity.AdvState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdvStateRepository extends JpaRepository<AdvState, Long> {

    Optional<AdvState> findByStateName(String stateName);

    boolean existsByStateName(String stateName);

    @Query("SELECT s FROM AdvState s ORDER BY s.stateName")
    List<AdvState> findAllOrderByName();

    @Query("SELECT s FROM AdvState s WHERE LOWER(s.stateName) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<AdvState> searchByStateName(@Param("keyword") String keyword);
}