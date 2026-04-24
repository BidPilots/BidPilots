package in.BidPilots.controller;

import in.BidPilots.dto.UserRegistration.UserResponseDTO;
import in.BidPilots.entity.User;
import in.BidPilots.repository.UserRegistrationRepository;
import in.BidPilots.service.SubscriptionService;
import in.BidPilots.service.UserLoginService;
import in.BidPilots.service.UserRegistration.UserRegistrationService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
public class UserDashboardController {

    private final UserLoginService userLoginService;
    private final UserRegistrationService userRegistrationService;
    private final SubscriptionService subscriptionService;
    private final UserRegistrationRepository userRepository;

    /**
     * Serve the main dashboard page - ONLY if user has active subscription
     */
    @GetMapping("/dashboard")
    public ModelAndView showDashboard() {
        log.info("📊 Serving user dashboard");
        
        // Check if user is authenticated
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
            log.warn("Unauthorized access to dashboard, redirecting to login");
            return new ModelAndView("redirect:/api/users/login-form");
        }
        
        try {
            String email = auth.getName();
            
            // Get user by email
            Map<String, Object> userResponse = userRegistrationService.getUserByEmail(email);
            if (!Boolean.TRUE.equals(userResponse.get("success"))) {
                log.warn("User not found: {}", email);
                return new ModelAndView("redirect:/api/users/login-form");
            }
            
            UserResponseDTO userDTO = (UserResponseDTO) userResponse.get("user");
            Long userId = userDTO.getId();
            
            // Check if user has active subscription
            if (!subscriptionService.hasActiveSubscription(userId)) {
                log.warn("User {} tried to access dashboard without active subscription", email);
                // Redirect to subscription plans page with expired flag
                return new ModelAndView("redirect:/subscription/plans?email=" + email + "&expired=true");
            }
            
            return new ModelAndView("userDashboard");
            
        } catch (Exception e) {
            log.error("Error checking subscription: {}", e.getMessage(), e);
            return new ModelAndView("redirect:/subscription/plans?expired=true");
        }
    }

    /**
     * Alternative dashboard URL
     */
    @GetMapping("/my-dashboard")
    public ModelAndView showMyDashboard() {
        return showDashboard();
    }

    /**
     * Get current user data - UPDATED to return full UserResponseDTO with GST
     */
    @GetMapping("/current")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getCurrentUser() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
                response.put("success", false);
                response.put("message", "Not authenticated");
                return ResponseEntity.status(401).body(response);
            }
            
            String email = auth.getName();
            
            // Use userRegistrationService which returns UserResponseDTO with full details including GST
            Map<String, Object> userResponse = userRegistrationService.getUserByEmail(email);
            
            if (Boolean.TRUE.equals(userResponse.get("success"))) {
                Object userObj = userResponse.get("user");
                
                // Convert to Map with all fields including GST
                Map<String, Object> userData = new HashMap<>();
                if (userObj instanceof UserResponseDTO dto) {
                    userData.put("id", dto.getId());
                    userData.put("companyName", dto.getCompanyName());
                    userData.put("email", dto.getEmail());
                    userData.put("mobileNumber", dto.getMobileNumber());
                    userData.put("gstNumber", dto.getGstNumber());  // ← GST NUMBER HERE
                    userData.put("isActive", dto.getIsActive());
                    userData.put("isEmailVerified", dto.getIsEmailVerified());
                    userData.put("createdAt", dto.getCreatedAt());
                    userData.put("emailVerifiedAt", dto.getEmailVerifiedAt());
                    userData.put("lastLoginAt", dto.getLastLoginAt());
                    userData.put("loginCount", dto.getLoginCount());
                    userData.put("role", dto.getRole());
                } else if (userObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) userObj;
                    userData = cast;
                }
                
                // Add subscription status
                Long userId = (Long) userData.get("id");
                boolean hasActiveSub = subscriptionService.hasActiveSubscription(userId);
                userData.put("hasActiveSubscription", hasActiveSub);
                
                response.put("success", true);
                response.put("user", userData);
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "User not found");
                return ResponseEntity.status(404).body(response);
            }
            
        } catch (Exception e) {
            log.error("Error getting current user: {}", e.getMessage());
            response.put("success", false);
            response.put("message", "Error fetching user data");
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Get user by email (used by dashboard)
     */
    @GetMapping("/user/{email}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getUserByEmail(@PathVariable String email) {
        log.info("Fetching user by email: {}", email);
        
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated"));
        }
        
        // Security check - users can only access their own data
        if (!auth.getName().equals(email)) {
            log.warn("User {} tried to access data for {}", auth.getName(), email);
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Access denied"));
        }
        
        Map<String, Object> response = userRegistrationService.getUserByEmail(email);
        
        if (response.containsKey("success") && (Boolean) response.get("success")) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(404).body(response);
        }
    }

    /**
     * Get dashboard statistics
     */
    @GetMapping("/dashboard/stats")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getDashboardStats(@RequestParam(required = false) Long userId) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated"));
            }
            
            // Check if user has active subscription before providing stats
            String email = auth.getName();
            User user = userRepository.findByEmail(email).orElse(null);
            if (user == null || !subscriptionService.hasActiveSubscription(user.getId())) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "Active subscription required"));
            }
            
            // TODO: Implement actual stats from your database
            // This is sample data - replace with real queries
            Map<String, Object> stats = new HashMap<>();
            stats.put("activeBids", 12);
            stats.put("wonBids", 5);
            stats.put("totalValue", 1250000); // ₹12.5 Lakhs
            
            response.put("success", true);
            response.put("stats", stats);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error getting dashboard stats: {}", e.getMessage());
            response.put("success", false);
            response.put("message", "Error fetching stats");
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Keep-alive endpoint to refresh session
     */
    @PostMapping("/keep-alive")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> keepAlive(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated"));
            }
            
            // Just return success to keep session alive
            response.put("success", true);
            response.put("message", "Session refreshed");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error in keep-alive: {}", e.getMessage());
            response.put("success", false);
            response.put("message", "Error refreshing session");
            return ResponseEntity.status(500).body(response);
        }
    }

    // Placeholder endpoints for navigation (to be implemented later)
    
    @GetMapping("/tenders")
    public ModelAndView showTenders() {
        checkAuthenticationAndSubscription();
        ModelAndView mav = new ModelAndView("userBids");
        mav.addObject("pageTitle", "My Bids");
        return mav;
    }

   
    @GetMapping("/settings")
    public ModelAndView showSettings() {
        checkAuthenticationAndSubscription();
        ModelAndView mav = new ModelAndView("userSettings");
        mav.addObject("pageTitle", "Settings");
        return mav;
    }

    @GetMapping("/new-bid")
    public ModelAndView showNewBid() {
        checkAuthenticationAndSubscription();
        ModelAndView mav = new ModelAndView("newBid");
        mav.addObject("pageTitle", "Create New Bid");
        return mav;
    }

    @GetMapping("/analytics")
    public ModelAndView showAnalytics() {
        checkAuthenticationAndSubscription();
        ModelAndView mav = new ModelAndView("userAnalytics");
        mav.addObject("pageTitle", "Analytics");
        return mav;
    }

    /**
     * Helper method to check authentication and subscription
     */
    private void checkAuthenticationAndSubscription() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
            throw new RuntimeException("Not authenticated");
        }
        
        try {
            String email = auth.getName();
            User user = userRepository.findByEmail(email).orElse(null);
            if (user == null || !subscriptionService.hasActiveSubscription(user.getId())) {
                throw new RuntimeException("Active subscription required");
            }
        } catch (Exception e) {
            throw new RuntimeException("Active subscription required");
        }
    }
    
    @GetMapping("/filters-page")
    public ModelAndView showFilterManager() {
        log.info("🔍 Serving filter manager page");
        
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
            log.warn("Unauthorized access to filter manager, redirecting to login");
            return new ModelAndView("redirect:/api/users/login-form");
        }
        
        // Check subscription before showing filter manager
        try {
            String email = auth.getName();
            User user = userRepository.findByEmail(email).orElse(null);
            if (user == null || !subscriptionService.hasActiveSubscription(user.getId())) {
                log.warn("User {} tried to access filters without active subscription", email);
                return new ModelAndView("redirect:/subscription/plans?email=" + email + "&expired=true");
            }
        } catch (Exception e) {
            log.error("Error checking subscription for filters page: {}", e.getMessage());
            return new ModelAndView("redirect:/subscription/plans?expired=true");
        }
        
        return new ModelAndView("userFilter");
    }

    // ========== NEW METHOD FOR ALL FILTERS PAGE ==========
    
    /**
     * Serve the All Filters page - shows all saved filters across all types
     */
    @GetMapping("/all-filters")
    public ModelAndView showAllFilters() {
        log.info("📋 Serving all filters page");
        
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
            log.warn("Unauthorized access to all filters, redirecting to login");
            return new ModelAndView("redirect:/api/users/login-form");
        }
        
        // Check subscription before showing all filters
        try {
            String email = auth.getName();
            User user = userRepository.findByEmail(email).orElse(null);
            if (user == null || !subscriptionService.hasActiveSubscription(user.getId())) {
                log.warn("User {} tried to access all filters without active subscription", email);
                return new ModelAndView("redirect:/subscription/plans?email=" + email + "&expired=true");
            }
        } catch (Exception e) {
            log.error("Error checking subscription for all filters page: {}", e.getMessage());
            return new ModelAndView("redirect:/subscription/plans?expired=true");
        }
        
        return new ModelAndView("allFilters");
    }
    
    @GetMapping("/profile")
    public ModelAndView showProfilePage() {
        checkAuthenticationAndSubscription();
        ModelAndView mav = new ModelAndView("userProfile");
        mav.addObject("pageTitle", "My Profile");
        return mav;
    }

    @PutMapping("/profile/update")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateProfile(@RequestBody Map<String, String> updates) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
                response.put("success", false);
                response.put("message", "Not authenticated");
                return ResponseEntity.status(401).body(response);
            }
            
            String email = auth.getName();
            User user = userRepository.findByEmail(email).orElse(null);
            
            if (user == null) {
                response.put("success", false);
                response.put("message", "User not found");
                return ResponseEntity.status(404).body(response);
            }
            
            // Update fields
            if (updates.containsKey("companyName") && updates.get("companyName") != null) {
                user.setCompanyName(updates.get("companyName").trim());
            }
            
            if (updates.containsKey("mobileNumber") && updates.get("mobileNumber") != null) {
                String mobile = updates.get("mobileNumber").trim();
                // Check if mobile already exists for another user
                User existingUser = userRepository.findByMobileNumber(mobile).orElse(null);
                if (existingUser != null && !existingUser.getId().equals(user.getId())) {
                    response.put("success", false);
                    response.put("message", "Mobile number already registered to another account");
                    return ResponseEntity.badRequest().body(response);
                }
                user.setMobileNumber(mobile);
            }
            
            if (updates.containsKey("gstNumber") && updates.get("gstNumber") != null) {
                String gst = updates.get("gstNumber").trim().toUpperCase();
                // Validate GST format
                String gstRegex = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}[Z]{1}[0-9A-Z]{1}$";
                if (!gst.matches(gstRegex)) {
                    response.put("success", false);
                    response.put("message", "Invalid GST number format");
                    return ResponseEntity.badRequest().body(response);
                }
                
                // Check if GST already exists for another user
                User existingUser = userRepository.findByGstNumber(gst).orElse(null);
                if (existingUser != null && !existingUser.getId().equals(user.getId())) {
                    response.put("success", false);
                    response.put("message", "GST number already registered to another account");
                    return ResponseEntity.badRequest().body(response);
                }
                user.setGstNumber(gst);
            }
            
            userRepository.save(user);
            
            response.put("success", true);
            response.put("message", "Profile updated successfully");
            response.put("user", Map.of(
                "companyName", user.getCompanyName(),
                "mobileNumber", user.getMobileNumber(),
                "gstNumber", user.getGstNumber(),
                "email", user.getEmail()
            ));
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error updating profile: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to update profile: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/profile/change-password")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody Map<String, String> passwordData) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
                response.put("success", false);
                response.put("message", "Not authenticated");
                return ResponseEntity.status(401).body(response);
            }
            
            String email = auth.getName();
            User user = userRepository.findByEmail(email).orElse(null);
            
            if (user == null) {
                response.put("success", false);
                response.put("message", "User not found");
                return ResponseEntity.status(404).body(response);
            }
            
            String currentPassword = passwordData.get("currentPassword");
            String newPassword = passwordData.get("newPassword");
            String confirmPassword = passwordData.get("confirmPassword");
            
            // Verify current password
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            if (!encoder.matches(currentPassword, user.getPassword())) {
                response.put("success", false);
                response.put("message", "Current password is incorrect");
                return ResponseEntity.badRequest().body(response);
            }
            
            // Check new password match
            if (!newPassword.equals(confirmPassword)) {
                response.put("success", false);
                response.put("message", "New passwords do not match");
                return ResponseEntity.badRequest().body(response);
            }
            
            // Validate password strength
            String passwordRegex = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=])(?=\\S+$).{8,}$";
            if (!newPassword.matches(passwordRegex)) {
                response.put("success", false);
                response.put("message", "Password must be 8+ characters with uppercase, lowercase, number, and special character");
                return ResponseEntity.badRequest().body(response);
            }
            
            // Update password
            user.setPassword(encoder.encode(newPassword));
            userRepository.save(user);
            
            response.put("success", true);
            response.put("message", "Password changed successfully");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error changing password: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to change password: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
}