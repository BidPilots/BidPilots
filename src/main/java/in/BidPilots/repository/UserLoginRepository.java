package in.BidPilots.repository;

import in.BidPilots.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface UserLoginRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);
    
    Optional<User> findByEmailAndIsActiveTrue(String email);
    
    boolean existsByEmail(String email);
    
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.lastLoginAt = :loginTime, u.loginCount = COALESCE(u.loginCount, 0) + 1 WHERE u.id = :userId")
    int updateLastLogin(@Param("userId") Long userId, @Param("loginTime") LocalDateTime loginTime);
    
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.failedAttempts = COALESCE(u.failedAttempts, 0) + 1 WHERE u.id = :userId")
    int incrementFailedAttempts(@Param("userId") Long userId);
    
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.failedAttempts = 0, u.lockTime = null WHERE u.id = :userId")
    int resetFailedAttempts(@Param("userId") Long userId);
    
    @Query("SELECT u.failedAttempts FROM User u WHERE u.id = :userId")
    Integer getFailedAttempts(@Param("userId") Long userId);
}