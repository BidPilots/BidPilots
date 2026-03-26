package in.BidPilots.repository;

import in.BidPilots.entity.City;
import in.BidPilots.entity.State;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface CityRepository extends JpaRepository<City, Long> {
    
    List<City> findByState(State state);
    
    List<City> findByStateId(Long stateId);
    
    Optional<City> findByCityNameAndState(String cityName, State state);
    
    @Query("SELECT c FROM City c WHERE c.state.id = :stateId AND c.isActive = true ORDER BY c.cityName")
    List<City> findActiveCitiesByState(@Param("stateId") Long stateId);
    
    long countByState(State state);
    
    @Modifying
    @Transactional
    @Query("DELETE FROM City c WHERE c.state = :state")
    void deleteByState(@Param("state") State state);
}