package in.BidPilots.dto.UserRegistration;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserResponseDTO {
    private Long id;
    private String companyName;
    private String email;
    private String mobileNumber;
    private String gstNumber;
    private Boolean isActive;
    private Boolean isEmailVerified;
    private LocalDateTime createdAt;
    private LocalDateTime emailVerifiedAt;
    
    public static UserResponseDTO fromUser(in.BidPilots.entity.User user) {
        if (user == null) return null;
        
        UserResponseDTO dto = new UserResponseDTO();
        dto.setId(user.getId());
        dto.setCompanyName(user.getCompanyName());
        dto.setEmail(user.getEmail());
        dto.setMobileNumber(user.getMobileNumber());
        dto.setGstNumber(user.getGstNumber());
        dto.setIsActive(user.getIsActive());
        dto.setIsEmailVerified(user.getIsEmailVerified());
        dto.setCreatedAt(user.getCreatedAt());
        dto.setEmailVerifiedAt(user.getEmailVerifiedAt());
        return dto;
    }
}