package in.BidPilots.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminDashboardController {

    /**
     * Serve the admin dashboard page
     */
    @GetMapping("/dashboard")
    public ModelAndView showAdminDashboard() {
        log.info("📊 Serving admin dashboard");
        
        // Check if user is authenticated
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
            log.warn("Unauthorized access to admin dashboard, redirecting to login");
            return new ModelAndView("redirect:/api/users/login-form");
        }
        
        return new ModelAndView("adminDashboard");
    }
}