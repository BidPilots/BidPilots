package in.BidPilots.repository;

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
public interface StateRepository extends JpaRepository<State, Long> {
    
    Optional<State> findByStateName(String stateName);
    
    @Query("SELECT s FROM State s WHERE s.isActive = true ORDER BY s.stateName")
    List<State> findAllActiveStates();
    
    @Query("SELECT s FROM State s WHERE LOWER(s.stateName) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<State> searchStates(@Param("keyword") String keyword);
    
    @Modifying
    @Transactional
    @Query("DELETE FROM State s WHERE s.stateName = :stateName")
    void deleteByStateName(@Param("stateName") String stateName);
}