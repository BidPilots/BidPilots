package in.BidPilots.repository;

import in.BidPilots.entity.UserSavedFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserSavedFilterRepository extends JpaRepository<UserSavedFilter, Long> {

    List<UserSavedFilter> findByUserId(Long userId);

    List<UserSavedFilter> findByUserIdAndFilterType(Long userId, String filterType);

    // FIX: Replaced void deleteByUserIdAndId with @Modifying JPQL query.
    // The derived delete method does a SELECT then DELETE (two round-trips).
    // This single-query version is more efficient and fully atomic.
    @Modifying
    @Query("DELETE FROM UserSavedFilter f WHERE f.userId = :userId AND f.id = :filterId")
    int deleteByUserIdAndId(@Param("userId") Long userId, @Param("filterId") Long filterId);

    boolean existsByUserIdAndFilterName(Long userId, String filterName);

    // FIX: Added — used to verify ownership before returning or deleting a filter
    Optional<UserSavedFilter> findByIdAndUserId(Long id, Long userId);

    // FIX: Added — fetch only filters that have a category (needed by matching service)
    @Query("SELECT f FROM UserSavedFilter f WHERE f.userId = :userId AND f.categoryId IS NOT NULL")
    List<UserSavedFilter> findByUserIdWithCategory(@Param("userId") Long userId);

    // FIX: Added — fetch ALL filters that have categories across all users (batch matching)
    @Query("SELECT f FROM UserSavedFilter f WHERE f.categoryId IS NOT NULL")
    List<UserSavedFilter> findAllWithCategory();
}