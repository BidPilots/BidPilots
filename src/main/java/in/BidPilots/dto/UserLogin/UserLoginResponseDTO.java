package in.BidPilots.dto.UserLogin;

import in.BidPilots.entity.User;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserLoginResponseDTO {
    private Long id;
    private String companyName;
    private String email;
    private String mobileNumber;
    private String gstNumber;        // ← ADD THIS FIELD
    private Boolean isActive;
    private Boolean isEmailVerified;
    private LocalDateTime lastLoginAt;
    private Integer loginCount;
    private String message;
    private String token;
    private String role;
    
    public static UserLoginResponseDTO fromUser(User user) {
        if (user == null) return null;
        
        UserLoginResponseDTO dto = new UserLoginResponseDTO();
        dto.setId(user.getId());
        dto.setCompanyName(user.getCompanyName());
        dto.setEmail(user.getEmail());
        dto.setMobileNumber(user.getMobileNumber());
        dto.setGstNumber(user.getGstNumber());    // ← ADD THIS LINE
        dto.setIsActive(user.getIsActive());
        dto.setIsEmailVerified(user.getIsEmailVerified());
        dto.setLastLoginAt(user.getLastLoginAt());
        dto.setLoginCount(user.getLoginCount());
        dto.setRole(user.getRole());
        return dto;
    }
}