package in.BidPilots.controller;

import in.BidPilots.dto.UserRegistration.*;
import in.BidPilots.service.UserRegistration.UserRegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class UserRegistrationController {

	private final UserRegistrationService userRegistrationService;

	@GetMapping("/register-form")
	public ModelAndView showRegistrationForm() {
		log.info("Serving user registration form");
		return new ModelAndView("userRegister");
	}

	@GetMapping("/register")
	public ModelAndView showRegistrationPage() {
		log.info("Serving user registration page");
		return new ModelAndView("userRegister");
	}

	@PostMapping("/initiate-registration")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> initiateRegistration(
			@Valid @RequestBody UserRegistrationDTO registrationDTO) {
		log.info("📝 Initiate registration for email: {}", registrationDTO.getEmail());

		Map<String, Object> response = userRegistrationService.initiateRegistration(registrationDTO);

		return response.containsKey("success") && (Boolean) response.get("success") ? ResponseEntity.ok(response)
				: ResponseEntity.badRequest().body(response);
	}

	@PostMapping("/verify-email-otp")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> verifyEmailOTP(@Valid @RequestBody OTPVerificationDTO otpDTO) {
		log.info("🔐 Verify Email OTP for email: {}", otpDTO.getEmail());

		Map<String, Object> response = userRegistrationService.verifyEmailOTP(otpDTO.getEmail(), otpDTO.getOtp());

		return response.containsKey("success") && (Boolean) response.get("success") ? ResponseEntity.ok(response)
				: ResponseEntity.badRequest().body(response);
	}

	@PostMapping("/resend-email-otp")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> resendEmailOTP(@Valid @RequestBody OTPRequestDTO otpRequest) {
		log.info("🔄 Resend Email OTP for email: {}", otpRequest.getEmail());

		Map<String, Object> response = userRegistrationService.resendEmailOTP(otpRequest.getEmail());

		return response.containsKey("success") && (Boolean) response.get("success") ? ResponseEntity.ok(response)
				: ResponseEntity.badRequest().body(response);
	}

	@GetMapping("/check-exists")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> checkUserExists(@RequestParam String email) {
		log.info("Check if user exists: {}", email);
		return ResponseEntity.ok(userRegistrationService.checkUserExists(email));
	}

	@GetMapping("/user/{email}")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> getUserByEmail(@PathVariable String email) {
		log.info("Fetch user by email: {}", email);

		Map<String, Object> response = userRegistrationService.getUserByEmail(email);

		return response.containsKey("success") && (Boolean) response.get("success") ? ResponseEntity.ok(response)
				: ResponseEntity.status(404).body(response);
	}

	@GetMapping("/health")
	@ResponseBody
	public ResponseEntity<Map<String, String>> healthCheck() {
		Map<String, String> response = new HashMap<>();
		response.put("status", "UP");
		response.put("service", "User Registration Service (Email OTP Only)");
		response.put("timestamp", java.time.LocalDateTime.now().toString());
		return ResponseEntity.ok(response);
	}
}