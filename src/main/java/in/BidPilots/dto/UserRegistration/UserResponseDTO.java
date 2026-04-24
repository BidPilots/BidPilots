package in.BidPilots.dto.UserRegistration;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserResponseDTO {
    private Long id;
    private String companyName;
    private String email;
    private String mobileNumber;
    private String gstNumber;        // ← ADD THIS FIELD
    private Boolean isActive;
    private Boolean isEmailVerified;
    private LocalDateTime createdAt;
    private LocalDateTime emailVerifiedAt;
    private LocalDateTime lastLoginAt;  // ← Also add this for last login
    private Integer loginCount;          // ← Also add this for login count
    private String role;                 // ← Also add this for role
    
    public static UserResponseDTO fromUser(in.BidPilots.entity.User user) {
        if (user == null) return null;
        
        UserResponseDTO dto = new UserResponseDTO();
        dto.setId(user.getId());
        dto.setCompanyName(user.getCompanyName());
        dto.setEmail(user.getEmail());
        dto.setMobileNumber(user.getMobileNumber());
        dto.setGstNumber(user.getGstNumber());           // ← ADD THIS LINE
        dto.setIsActive(user.getIsActive());
        dto.setIsEmailVerified(user.getIsEmailVerified());
        dto.setCreatedAt(user.getCreatedAt());
        dto.setEmailVerifiedAt(user.getEmailVerifiedAt());
        dto.setLastLoginAt(user.getLastLoginAt());       // ← ADD THIS LINE
        dto.setLoginCount(user.getLoginCount());         // ← ADD THIS LINE
        dto.setRole(user.getRole());                     // ← ADD THIS LINE
        return dto;
    }
}