package in.BidPilots.controller;

import in.BidPilots.dto.UserLogin.ForgotPasswordRequestDTO;
import in.BidPilots.dto.UserLogin.ResetPasswordRequestDTO;
import in.BidPilots.dto.UserLogin.UserLoginDTO;
import in.BidPilots.dto.UserLogin.UserLoginResponseDTO;
import in.BidPilots.dto.UserRegistration.OTPVerificationDTO;
import in.BidPilots.service.UserLoginService;
import in.BidPilots.service.UserRegistration.ForgotPasswordService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Slf4j
// FIX: Restrict CORS origins — "*" is forbidden when allowCredentials=true
@CrossOrigin(origins = {"http://localhost:8080", "http://10.152.10.210:8080"}, allowCredentials = "true")
public class UserLoginController {

    private final UserLoginService    userLoginService;
    private final AuthenticationManager authenticationManager;
    private final ForgotPasswordService forgotPasswordService;

    // ── Login form pages ──────────────────────────────────────────────────────

    @GetMapping("/login-form")
    public ModelAndView showLoginForm() {
        return new ModelAndView("login");
    }

    @GetMapping("/login")
    public ModelAndView showLoginPage() {
        return new ModelAndView("login");
    }

    // ── JSON login (AJAX) ─────────────────────────────────────────────────────

    @PostMapping(value = "/login", produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> login(
            @Valid @RequestBody UserLoginDTO loginDTO,
            HttpServletRequest request) {

        log.info("🔐 Login request for: {}", loginDTO.getEmail());
        Map<String, Object> response = new HashMap<>();

        // Honour ?redirect=<url> so "Pay Now" on index page lands on the subscription page
        // after a successful login instead of the dashboard.
        String redirectParam = request.getParameter("redirect");

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginDTO.getEmail(), loginDTO.getPassword()));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            HttpSession session = request.getSession(true);
            session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

            Map<String, Object> svc = userLoginService.login(loginDTO);

            if (Boolean.TRUE.equals(svc.get("success"))) {
                Object userObj = svc.get("user");
                String role = "USER";
                Map<String, Object> userMap = new HashMap<>();

                if (userObj instanceof UserLoginResponseDTO dto) {
                    role = dto.getRole() != null ? dto.getRole() : "USER";
                    userMap.put("id",              dto.getId());
                    userMap.put("companyName",     dto.getCompanyName());
                    userMap.put("email",           dto.getEmail());
                    userMap.put("mobileNumber",    dto.getMobileNumber());
                    userMap.put("isActive",        dto.getIsActive());
                    userMap.put("isEmailVerified", dto.getIsEmailVerified());
                    userMap.put("lastLoginAt",     dto.getLastLoginAt());
                    userMap.put("loginCount",      dto.getLoginCount());
                    userMap.put("role",            role);
                } else if (userObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) userObj;
                    userMap = cast;
                    role    = userMap.getOrDefault("role", "USER").toString();
                }

                Map<String, Object> out = new HashMap<>();
                out.put("success",     true);
                out.put("message",     svc.get("message"));
                out.put("user",        userMap);
                out.put("role",        role);
                out.put("sessionId",   session.getId());
                // Resolve post-login destination: honour safe redirect param if provided
                String defaultDashboard = "ADMIN".equalsIgnoreCase(role)
                        ? "/api/admin/dashboard" : "/api/user/dashboard";
                String resolvedRedirect = resolveRedirectUrl(redirectParam, defaultDashboard);
                out.put("redirectUrl", resolvedRedirect);

                log.info("✅ Login success: {} (role={}) → {}", loginDTO.getEmail(), role, resolvedRedirect);
                return ResponseEntity.ok(out);

            } else {
                // Subscription expired — include redirect URL for plans page
                if (Boolean.TRUE.equals(svc.get("subscriptionExpired"))) {
                    String url = "/subscription/plans?userId=" + svc.getOrDefault("userId", "")
                            + "&email=" + svc.getOrDefault("email", "") + "&expired=true";
                    svc.put("redirectTo", url);
                    log.warn("🔒 Subscription expired: {}", loginDTO.getEmail());
                }
                return ResponseEntity.badRequest().body(svc);
            }

        } catch (BadCredentialsException e) {
            response.put("success", false);
            response.put("message", "Invalid email or password");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        } catch (DisabledException e) {
            response.put("success", false);
            response.put("message", "Account is disabled");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        } catch (LockedException e) {
            response.put("success", false);
            response.put("message", "Account is locked. Please try again later.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        } catch (Exception e) {
            log.error("Login error: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Login failed. Please try again.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // ── Form (non-AJAX) login ─────────────────────────────────────────────────

    @PostMapping("/form-login")
    public ModelAndView formLogin(@Valid UserLoginDTO loginDTO, HttpServletRequest request) {
        log.info("📝 Form login for: {}", loginDTO.getEmail());
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginDTO.getEmail(), loginDTO.getPassword()));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            HttpSession session = request.getSession(true);
            session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

            Map<String, Object> svc = userLoginService.login(loginDTO);
            ModelAndView mav = new ModelAndView();

            if (Boolean.TRUE.equals(svc.get("success"))) {
                String role = "USER";
                Object userObj = svc.get("user");
                if (userObj instanceof UserLoginResponseDTO dto)
                    role = dto.getRole() != null ? dto.getRole() : "USER";
                else if (userObj instanceof Map map)
                    role = map.getOrDefault("role", "USER").toString();

                mav.setViewName("ADMIN".equalsIgnoreCase(role)
                        ? "redirect:/api/admin/dashboard"
                        : "redirect:/api/user/dashboard");
                mav.addObject("user", svc.get("user"));
            } else if (Boolean.TRUE.equals(svc.get("subscriptionExpired"))) {
                String url = "/subscription/plans?userId=" + svc.getOrDefault("userId", "")
                        + "&email=" + svc.getOrDefault("email", "") + "&expired=true";
                mav.setViewName("redirect:" + url);
            } else {
                mav.setViewName("login");
                mav.addObject("error", svc.get("message"));
                mav.addObject("email", loginDTO.getEmail());
            }
            return mav;

        } catch (BadCredentialsException e) {
            ModelAndView mav = new ModelAndView("login");
            mav.addObject("error", "Invalid email or password");
            mav.addObject("email", loginDTO.getEmail());
            return mav;
        } catch (Exception e) {
            log.error("Form login error: {}", e.getMessage(), e);
            ModelAndView mav = new ModelAndView("login");
            mav.addObject("error", "Login failed. Please try again.");
            mav.addObject("email", loginDTO.getEmail());
            return mav;
        }
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @PostMapping("/logout")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> logout(
            HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null)
            new SecurityContextLogoutHandler().logout(request, response, auth);
        return ResponseEntity.ok(Map.of("success", true, "message", "Logged out successfully"));
    }

    // ── Session & current user ─────────────────────────────────────────────────

    @GetMapping("/session-status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> checkSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Map<String, Object> response = new HashMap<>();
        if (session != null) {
            response.put("hasSession", true);
            response.put("sessionId", session.getId());
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                response.put("authenticated", true);
                response.put("username", auth.getName());
                response.put("roles", auth.getAuthorities().toString());
            }
        } else {
            response.put("hasSession", false);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/current-user")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getCurrentUser() {
        Map<String, Object> response = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            response.put("success",       true);
            response.put("authenticated", true);
            response.put("username",      auth.getName());
            response.put("roles",         auth.getAuthorities().toString());
            Map<String, Object> userResponse = userLoginService.getUserByEmail(auth.getName());
            if (Boolean.TRUE.equals(userResponse.get("success")))
                response.put("user", userResponse.get("user"));
        } else {
            response.put("success",       false);
            response.put("authenticated", false);
            response.put("message",       "Not authenticated");
        }
        return ResponseEntity.ok(response);
    }

    // ── Forgot / reset password ───────────────────────────────────────────────

    @PostMapping("/forgot-password")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequestDTO req) {
        log.info("🔐 Forgot password for: {}", req.getEmail());
        Map<String, Object> res = forgotPasswordService.sendPasswordResetOTP(req.getEmail());
        return Boolean.TRUE.equals(res.get("success"))
                ? ResponseEntity.ok(res)
                : ResponseEntity.badRequest().body(res);
    }

    /**
     * Verify the OTP and get back a short-lived reset token (not the OTP).
     * The client passes this token to /reset-password.
     */
    @PostMapping("/verify-reset-otp")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> verifyResetOTP(
            @Valid @RequestBody OTPVerificationDTO req) {
        log.info("🔐 Verify reset OTP for: {}", req.getEmail());
        Map<String, Object> res = forgotPasswordService.verifyResetOTP(req.getEmail(), req.getOtp());
        return Boolean.TRUE.equals(res.get("success"))
                ? ResponseEntity.ok(res)
                : ResponseEntity.badRequest().body(res);
    }

    /**
     * PRODUCTION FIX: accepts resetToken (UUID from verifyResetOTP) instead of raw OTP.
     * ResetPasswordRequestDTO must have a resetToken field (replacing the old otp field).
     */
    @PostMapping("/reset-password")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resetPassword(
            @Valid @RequestBody ResetPasswordRequestDTO req) {
        log.info("🔐 Reset password for: {}", req.getEmail());

        if (!req.isPasswordMatch()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Passwords do not match"));
        }

        Map<String, Object> res = forgotPasswordService.resetPassword(
                req.getEmail(),
                req.getResetToken(),   // server-side token, NOT the OTP
                req.getNewPassword(),
                req.getConfirmPassword());

        return Boolean.TRUE.equals(res.get("success"))
                ? ResponseEntity.ok(res)
                : ResponseEntity.badRequest().body(res);
    }

    @PostMapping("/resend-password-otp")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resendPasswordOTP(
            @Valid @RequestBody ForgotPasswordRequestDTO req) {
        log.info("🔄 Resend password OTP for: {}", req.getEmail());
        Map<String, Object> res = forgotPasswordService.resendPasswordResetOTP(req.getEmail());
        return Boolean.TRUE.equals(res.get("success"))
                ? ResponseEntity.ok(res)
                : ResponseEntity.badRequest().body(res);
    }
    /**
     * Validates and resolves the post-login redirect URL.
     * Only allows relative URLs starting with "/" to prevent open-redirect attacks.
     * Falls back to defaultUrl if redirectParam is null, blank, or external.
     */
    private String resolveRedirectUrl(String redirectParam, String defaultUrl) {
        if (redirectParam == null || redirectParam.isBlank()) return defaultUrl;
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(redirectParam, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return defaultUrl;
        }
        // Block external URLs and protocol-relative URLs
        if (!decoded.startsWith("/") || decoded.startsWith("//")) return defaultUrl;
        return decoded;
    }

}