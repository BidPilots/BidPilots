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
@CrossOrigin(origins = { "http://localhost:8080", "http://10.152.10.210:8080" }, allowCredentials = "true")
public class UserLoginController {

	private final UserLoginService userLoginService;
	private final AuthenticationManager authenticationManager;
	private final ForgotPasswordService forgotPasswordService;

	@GetMapping("/login-form")
	public ModelAndView showLoginForm() {
		return new ModelAndView("login");
	}

	@GetMapping("/login")
	public ModelAndView showLoginPage() {
		return new ModelAndView("login");
	}

	@PostMapping(value = "/login", produces = "application/json")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody UserLoginDTO loginDTO,
			HttpServletRequest request) {
		log.info("🔐 Login request for email: {}", loginDTO.getEmail());
		Map<String, Object> response = new HashMap<>();

		try {
			// Authenticate with Spring Security
			Authentication authentication = authenticationManager
					.authenticate(new UsernamePasswordAuthenticationToken(loginDTO.getEmail(), loginDTO.getPassword()));
			SecurityContextHolder.getContext().setAuthentication(authentication);
			HttpSession session = request.getSession(true);
			session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

			Map<String, Object> serviceResponse = userLoginService.login(loginDTO);

			if (serviceResponse.containsKey("success") && (Boolean) serviceResponse.get("success")) {
				// ── Successful login ──────────────────────────────────────────
				Object userObj = serviceResponse.get("user");
				String role = "USER";
				Map<String, Object> userMap = new HashMap<>();

				if (userObj instanceof UserLoginResponseDTO userDto) {
					role = userDto.getRole() != null ? userDto.getRole() : "USER";
					userMap.put("id", userDto.getId());
					userMap.put("companyName", userDto.getCompanyName());
					userMap.put("email", userDto.getEmail());
					userMap.put("mobileNumber", userDto.getMobileNumber());
					userMap.put("isActive", userDto.getIsActive());
					userMap.put("isEmailVerified", userDto.getIsEmailVerified());
					userMap.put("lastLoginAt", userDto.getLastLoginAt());
					userMap.put("loginCount", userDto.getLoginCount());
					userMap.put("role", role);
				} else if (userObj instanceof Map) {
					userMap = (Map<String, Object>) userObj;
					role = userMap.getOrDefault("role", "USER").toString();
				} else {
					userMap.put("role", role);
				}

				Map<String, Object> finalResponse = new HashMap<>();
				finalResponse.put("success", true);
				finalResponse.put("message", serviceResponse.get("message"));
				finalResponse.put("user", userMap);
				finalResponse.put("role", role);
				finalResponse.put("sessionId", session.getId());

				if ("ADMIN".equalsIgnoreCase(role)) {
					finalResponse.put("redirectUrl", "/api/admin/dashboard");
					log.info("✅ Admin login successful");
				} else {
					finalResponse.put("redirectUrl", "/api/user/dashboard");
					log.info("✅ User login successful");
				}
				return ResponseEntity.ok().header("Content-Type", "application/json").body(finalResponse);

			} else {
				// ── Failed — check if subscription expired ────────────────────
				// Build redirect URL for the subscription page if expired
				if (Boolean.TRUE.equals(serviceResponse.get("subscriptionExpired"))) {
					String redirectUrl = "/subscription/plans" + "?userId=" + serviceResponse.getOrDefault("userId", "")
							+ "&email=" + serviceResponse.getOrDefault("email", "") + "&expired=true";
					serviceResponse.put("redirectTo", redirectUrl);
					log.warn("🔒 Subscription expired — redirecting to plans page");
				}
				return ResponseEntity.badRequest().header("Content-Type", "application/json").body(serviceResponse);
			}

		} catch (BadCredentialsException e) {
			log.error("Bad credentials for: {}", loginDTO.getEmail());
			response.put("success", false);
			response.put("message", "Invalid email or password");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
		} catch (DisabledException e) {
			response.put("success", false);
			response.put("message", "Account is disabled");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
		} catch (LockedException e) {
			response.put("success", false);
			response.put("message", "Account is locked");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
		} catch (Exception e) {
			log.error("Authentication failed: {}", e.getMessage(), e);
			response.put("success", false);
			response.put("message", "Invalid email or password");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
		}
	}

	@PostMapping("/form-login")
	public ModelAndView formLogin(@Valid UserLoginDTO loginDTO, HttpServletRequest request) {
		log.info("📝 Form login for email: {}", loginDTO.getEmail());
		try {
			Authentication authentication = authenticationManager
					.authenticate(new UsernamePasswordAuthenticationToken(loginDTO.getEmail(), loginDTO.getPassword()));
			SecurityContextHolder.getContext().setAuthentication(authentication);
			HttpSession session = request.getSession(true);
			session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

			Map<String, Object> response = userLoginService.login(loginDTO);
			ModelAndView mav = new ModelAndView();

			if (response.containsKey("success") && (Boolean) response.get("success")) {
				String role = "USER";
				Object userObj = response.get("user");
				if (userObj instanceof UserLoginResponseDTO userDto)
					role = userDto.getRole() != null ? userDto.getRole() : "USER";
				else if (userObj instanceof Map userMap)
					role = userMap.getOrDefault("role", "USER").toString();

				mav.setViewName("ADMIN".equalsIgnoreCase(role) ? "redirect:/api/admin/dashboard"
						: "redirect:/api/user/dashboard");
				mav.addObject("user", response.get("user"));
			} else if (Boolean.TRUE.equals(response.get("subscriptionExpired"))) {
				// Redirect directly to subscription plans page
				String url = "/subscription/plans?userId=" + response.getOrDefault("userId", "") + "&email="
						+ response.getOrDefault("email", "") + "&expired=true";
				mav.setViewName("redirect:" + url);
			} else {
				mav.setViewName("login");
				mav.addObject("error", response.get("message"));
				mav.addObject("email", loginDTO.getEmail());
			}
			return mav;

		} catch (BadCredentialsException e) {
			ModelAndView mav = new ModelAndView("login");
			mav.addObject("error", "Invalid email or password");
			mav.addObject("email", loginDTO.getEmail());
			return mav;
		} catch (Exception e) {
			ModelAndView mav = new ModelAndView("login");
			mav.addObject("error", "Invalid email or password");
			mav.addObject("email", loginDTO.getEmail());
			return mav;
		}
	}

	@PostMapping("/logout")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request, HttpServletResponse response) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null)
			new SecurityContextLogoutHandler().logout(request, response, auth);
		Map<String, Object> result = new HashMap<>();
		result.put("success", true);
		result.put("message", "Logged out successfully");
		return ResponseEntity.ok(result);
	}

	@GetMapping("/session-status")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> checkSession(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		Map<String, Object> response = new HashMap<>();
		if (session != null) {
			response.put("hasSession", true);
			response.put("sessionId", session.getId());
			Authentication auth = SecurityContextHolder.getContext().getAuthentication();
			if (auth != null && auth.isAuthenticated() && !auth.getPrincipal().equals("anonymousUser")) {
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
		if (auth != null && auth.isAuthenticated() && !auth.getPrincipal().equals("anonymousUser")) {
			response.put("success", true);
			response.put("authenticated", true);
			response.put("username", auth.getName());
			response.put("roles", auth.getAuthorities().toString());
			Map<String, Object> userResponse = userLoginService.getUserByEmail(auth.getName());
			if (Boolean.TRUE.equals(userResponse.get("success")))
				response.put("user", userResponse.get("user"));
		} else {
			response.put("success", false);
			response.put("authenticated", false);
			response.put("message", "Not authenticated");
		}
		return ResponseEntity.ok(response);
	}

	// ─────────────────────────────────────────────────────────────
	// FORGOT PASSWORD ENDPOINTS
	// ─────────────────────────────────────────────────────────────

	/**
	 * Send OTP for password reset
	 */
	@PostMapping("/forgot-password")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> forgotPassword(@Valid @RequestBody ForgotPasswordRequestDTO request) {
		log.info("🔐 Forgot password request for email: {}", request.getEmail());

		Map<String, Object> response = forgotPasswordService.sendPasswordResetOTP(request.getEmail());

		if (response.containsKey("success") && (Boolean) response.get("success")) {
			return ResponseEntity.ok(response);
		} else {
			return ResponseEntity.badRequest().body(response);
		}
	}

	/**
	 * Reset password with OTP verification
	 */
	@PostMapping("/reset-password")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> resetPassword(@Valid @RequestBody ResetPasswordRequestDTO request) {
		log.info("🔐 Reset password request for email: {}", request.getEmail());

		// Validate password match
		if (!request.isPasswordMatch()) {
			Map<String, Object> error = new HashMap<>();
			error.put("success", false);
			error.put("message", "Passwords do not match");
			return ResponseEntity.badRequest().body(error);
		}

		Map<String, Object> response = forgotPasswordService.resetPassword(request.getEmail(), request.getOtp(),
				request.getNewPassword(), request.getConfirmPassword());

		if (response.containsKey("success") && (Boolean) response.get("success")) {
			return ResponseEntity.ok(response);
		} else {
			return ResponseEntity.badRequest().body(response);
		}
	}

	/**
	 * Resend OTP for password reset
	 */
	@PostMapping("/resend-password-otp")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> resendPasswordOTP(@Valid @RequestBody ForgotPasswordRequestDTO request) {
		log.info("🔄 Resend password OTP request for email: {}", request.getEmail());

		Map<String, Object> response = forgotPasswordService.resendPasswordResetOTP(request.getEmail());

		if (response.containsKey("success") && (Boolean) response.get("success")) {
			return ResponseEntity.ok(response);
		} else {
			return ResponseEntity.badRequest().body(response);
		}
	}

	

	/**
	 * Verify OTP for password reset (separate from registration OTP)
	 */
	@PostMapping("/verify-reset-otp")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> verifyResetOTP(@Valid @RequestBody OTPVerificationDTO request) {
		log.info("🔐 Verify password reset OTP for email: {}", request.getEmail());

		Map<String, Object> response = forgotPasswordService.verifyResetOTP(request.getEmail(), request.getOtp());

		if (response.containsKey("success") && (Boolean) response.get("success")) {
			return ResponseEntity.ok(response);
		} else {
			return ResponseEntity.badRequest().body(response);
		}
	}
}