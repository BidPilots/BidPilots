package in.BidPilots.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SaveFilterRequest {

    // FIX: userId is now REMOVED from request body. It is resolved from the JWT
    // inside the service/controller, preventing users from saving filters on behalf
    // of other users (IDOR vulnerability). See UserSavedFilterService.saveFilter().

    @NotBlank(message = "Filter name is required")
    @Size(max = 100, message = "Filter name cannot exceed 100 characters")
    private String filterName;

    @Size(max = 20, message = "Filter type cannot exceed 20 characters")
    private String filterType;

    private List<Long> stateIds;
    private List<Long> cityIds;

    @NotNull(message = "Category is required")
    private Long categoryId;
}