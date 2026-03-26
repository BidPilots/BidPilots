package in.BidPilots.repository;

import in.BidPilots.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByCategoryName(String categoryName);

    @Query("SELECT c FROM Category c WHERE c.isActive = true ORDER BY c.categoryName")
    List<Category> findAllActiveCategories();

    boolean existsByCategoryName(String categoryName);
    
    @Query("SELECT c.categoryName FROM Category c")
    List<String> findAllCategoryNames();
    
    @Query("SELECT COUNT(c) FROM Category c")
    long countCategories();

    @Query("SELECT c FROM Category c WHERE LOWER(c.categoryName) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Category> searchCategories(@Param("keyword") String keyword);
}