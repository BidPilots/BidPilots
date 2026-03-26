package in.BidPilots.dto;

import in.BidPilots.entity.Category;
import lombok.Data;

@Data
public class CategoryDTO {
    private Long id;
    private String categoryName;
    private Boolean isActive;
    private Boolean isDeactive;

    public static CategoryDTO fromCategory(Category category) {
        if (category == null) return null;

        CategoryDTO dto = new CategoryDTO();
        dto.setId(category.getId());
        dto.setCategoryName(category.getCategoryName());
        dto.setIsActive(category.getIsActive());
        dto.setIsDeactive(category.getIsDeactive());

        return dto;
    }
}