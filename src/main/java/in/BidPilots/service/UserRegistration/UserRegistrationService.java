package in.BidPilots.service.UserRegistration;

import in.BidPilots.dto.UserRegistration.UserRegistrationDTO;
import in.BidPilots.dto.UserRegistration.UserResponseDTO;
import in.BidPilots.entity.Subscription;
import in.BidPilots.entity.User;
import in.BidPilots.enums.PlanDuration; // unified — also covers ex-SubscriptionPlan
import in.BidPilots.enums.SubscriptionStatus;
import in.BidPilots.repository.SubscriptionRepository;
import in.BidPilots.repository.UserRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserRegistrationService {

	private final UserRegistrationRepository userRepository;
	private final SubscriptionRepository subscriptionRepository;
	private final EmailOTPService emailOTPService;
	private final BCryptPasswordEncoder passwordEncoder;

	// In-memory temp storage — holds pre-verified registration data until OTP
	// confirmed.
	// In production you would replace this with Redis (TTL-based eviction).
	private static final Map<String, RegistrationTempData> tempStorage = new ConcurrentHashMap<>();

	// Auto-clean stale entries to prevent unbounded memory growth.
	// Called at the start of every initiateRegistration() call.
	private static final int MAX_TEMP_ENTRIES = 10_000;

	@lombok.Data
	@lombok.AllArgsConstructor
	private static class RegistrationTempData {
		private UserRegistrationDTO userData;
		private LocalDateTime expiry;
		private int attempts;

		boolean isExpired() {
			return LocalDateTime.now().isAfter(expiry);
		}
	}

	// GST validation helper
	private boolean isValidGST(String gst) {
		if (!StringUtils.hasText(gst))
			return false;
		// GST format: 22AAAAA0000A1Z5
		// 2 digit state code + 10 chars PAN + 1 digit entity number + 1 alphabet + 1
		// check digit
		String gstRegex = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}[Z]{1}[0-9A-Z]{1}$";
		return gst.matches(gstRegex);
	}

	// ─────────────────────────────────────────────────────────────
	// STEP 1 — Initiate registration: validate, encode password, send OTP
	// ─────────────────────────────────────────────────────────────

	public Map<String, Object> initiateRegistration(UserRegistrationDTO dto) {
		Map<String, Object> response = new HashMap<>();
		try {
			String email = dto.getEmail() != null ? dto.getEmail().trim().toLowerCase() : null;
			String mobile = dto.getMobileNumber() != null ? dto.getMobileNumber().trim() : null;
			String gst = dto.getGstNumber() != null ? dto.getGstNumber().trim().toUpperCase() : null;

			// Null/blank guard
			if (!StringUtils.hasText(email)) {
				return error("Email address is required.");
			}
			if (!StringUtils.hasText(mobile)) {
				return error("Mobile number is required.");
			}
			if (!StringUtils.hasText(gst)) {
				return error("GST number is required.");
			}

			log.info("📝 Registration initiated for: {}", email);

			// Validate GST format
			if (!isValidGST(gst)) {
				return error("Invalid GST number format. Format should be like: 22AAAAA0000A1Z5");
			}

			// Check email duplicate (only block if fully registered)
			Optional<User> existing = userRepository.findByEmail(email);
			if (existing.isPresent() && Boolean.TRUE.equals(existing.get().getRegistrationCompleted())) {
				return error("This email is already registered. Please login instead.");
			}

			// Check mobile duplicate
			if (userRepository.existsByMobileNumber(mobile)) {
				return error("This mobile number is already registered.");
			}

			// Check GST duplicate
			if (userRepository.existsByGstNumber(gst)) {
				return error("This GST number is already registered. Please contact support if this is your business.");
			}

			// Encode password in memory — never store plain text even in temp map
			dto.setEmail(email);
			dto.setGstNumber(gst);
			dto.setPassword(passwordEncoder.encode(dto.getPassword()));

			// Evict stale sessions before inserting a new one
			evictExpiredSessions();

			tempStorage.put(email, new RegistrationTempData(dto, LocalDateTime.now().plusMinutes(30), 0));

			emailOTPService.generateAndSendOTP(email);

			log.info("📧 OTP dispatched to: {}", email);
			response.put("success", true);
			response.put("message", "OTP sent to " + email + ". Valid for 10 minutes.");
			response.put("email", email);
			response.put("nextStep", "verify-email-otp");

		} catch (Exception e) {
			log.error("❌ initiateRegistration failed: {}", e.getMessage(), e);
			response.put("success", false);
			response.put("message", "Registration could not be started. Please try again.");
		}
		return response;
	}

	// ─────────────────────────────────────────────────────────────
	// STEP 2 — Verify OTP: confirm email, create User, activate trial
	// ─────────────────────────────────────────────────────────────

	@Transactional
	public Map<String, Object> verifyEmailOTP(String email, String otp) {
		if (!StringUtils.hasText(email) || !StringUtils.hasText(otp)) {
			return error("Email and OTP are both required.");
		}
		email = email.trim().toLowerCase();

		try {
			log.info("🔐 OTP verification attempt for: {}", email);

			// 1. Verify OTP (in-memory check via EmailOTPService)
			Map<String, Object> otpResult = emailOTPService.verifyOTP(email, otp);
			if (!Boolean.TRUE.equals(otpResult.get("success"))) {
				return otpResult; // propagates "Invalid OTP" / "OTP expired" etc.
			}

			// 2. Pull temp registration data
			RegistrationTempData temp = tempStorage.get(email);
			if (temp == null) {
				return error("No pending registration found for this email. Please start over.");
			}
			if (temp.isExpired()) {
				tempStorage.remove(email);
				return error("Your registration session has expired (30 min limit). Please start over.");
			}

			UserRegistrationDTO dto = temp.getUserData();

			// 3. Final race-condition duplicate guards (between OTP send and now)
			if (userRepository.existsByEmail(email)) {
				tempStorage.remove(email);
				return error("This email was registered while you were verifying. Please login.");
			}
			if (userRepository.existsByMobileNumber(dto.getMobileNumber())) {
				tempStorage.remove(email);
				return error("This mobile number was registered while you were verifying.");
			}
			if (userRepository.existsByGstNumber(dto.getGstNumber())) {
				tempStorage.remove(email);
				return error("This GST number was registered while you were verifying.");
			}

			// 4. Persist the User
			User user = new User();
			user.setEmail(email);
			user.setCompanyName(dto.getCompanyName().trim());
			user.setMobileNumber(dto.getMobileNumber().trim());
			user.setGstNumber(dto.getGstNumber());
			user.setPassword(dto.getPassword()); // already BCrypt-encoded in step 1
			user.setIsEmailVerified(true);
			user.setEmailVerifiedAt(LocalDateTime.now());
			user.setRegistrationCompleted(true);
			user.setIsActive(true);
			user.setRole("USER");
			user.setLoginCount(0);
			user.setFailedAttempts(0);

			User saved;
			try {
				saved = userRepository.save(user);
			} catch (DataIntegrityViolationException e) {
				// Race-condition catch: another request inserted the same email/mobile/GST
				// concurrently
				log.warn("⚠️ Duplicate key on save for email={}: {}", email, e.getMessage());
				tempStorage.remove(email);
				return error("This email, mobile, or GST is already registered. Please login.");
			}

			log.info("✅ User persisted — id={} email={} gst={}", saved.getId(), saved.getEmail(), saved.getGstNumber());

			// 5. Activate free trial (never block registration if this fails)
			try {
				activateFreeTrial(saved.getId());
				log.info("✅ 30-day trial activated for userId={}", saved.getId());
			} catch (Exception e) {
				log.error("❌ Trial activation failed for userId={}: {}", saved.getId(), e.getMessage(), e);
				// Trial failure is non-fatal — user can still log in and subscribe later
			}

			// 6. Clean up temp state
			tempStorage.remove(email);

			// 7. Send welcome email asynchronously (best-effort, non-fatal)
			try {
				emailOTPService.sendWelcomeEmail(saved.getEmail(), saved.getCompanyName());
			} catch (Exception e) {
				log.warn("Welcome email failed for {}: {}", email, e.getMessage());
			}

			// 8. Return success
			Map<String, Object> result = new HashMap<>();
			result.put("success", true);
			result.put("message", "Registration complete! Your 30-day free trial is now active.");
			result.put("userId", saved.getId());
			result.put("email", saved.getEmail());
			result.put("companyName", saved.getCompanyName());
			result.put("gstNumber", saved.getGstNumber());
			result.put("trialActivated", true);
			result.put("trialDays", 30);
			result.put("redirectTo", "/api/users/login-form");
			return result;

		} catch (Exception e) {
			log.error("❌ verifyEmailOTP failed for {}: {}", email, e.getMessage(), e);
			return error("Verification failed due to a server error. Please try again.");
		}
	}

	// ─────────────────────────────────────────────────────────────
	// Internal — activate trial subscription for a newly registered user
	// ─────────────────────────────────────────────────────────────

	@Transactional
	protected void activateFreeTrial(Long userId) {
		if (subscriptionRepository.existsByUserId(userId)) {
			log.warn("⚠️ Trial already exists for userId={} — skipping", userId);
			return;
		}

		PlanDuration plan = PlanDuration.TRIAL; // single source of truth
		LocalDateTime now = LocalDateTime.now();

		Subscription trial = new Subscription();
		trial.setUserId(userId);
		trial.setPlanDuration(plan);
		trial.setStatus(SubscriptionStatus.TRIAL);
		trial.setStartDate(now);
		trial.setEndDate(now.plusDays(plan.getDurationDays())); // 30 — driven by PlanDuration
		trial.setAmountPaidPaise(plan.getPricePaise()); // 0 — driven by PlanDuration
		subscriptionRepository.save(trial);
		log.info("\u2705 Trial subscription created \u2014 userId={} plan={} expires={}", userId, plan.getDisplayName(),
				trial.getEndDate());
	}

	// ─────────────────────────────────────────────────────────────
	// Resend OTP
	// ─────────────────────────────────────────────────────────────

	public Map<String, Object> resendEmailOTP(String email) {
		if (!StringUtils.hasText(email)) {
			return error("Email is required.");
		}
		email = email.trim().toLowerCase();
		try {
			RegistrationTempData temp = tempStorage.get(email);
			if (temp == null) {
				return error("No pending registration found. Please start registration again.");
			}
			if (temp.isExpired()) {
				tempStorage.remove(email);
				return error("Session expired. Please start registration again.");
			}
			return emailOTPService.resendOTP(email);
		} catch (Exception e) {
			log.error("❌ resendOTP failed for {}: {}", email, e.getMessage());
			return error("Could not resend OTP. Please try again.");
		}
	}

	// ─────────────────────────────────────────────────────────────
	// Utility queries
	// ─────────────────────────────────────────────────────────────

	public Map<String, Object> checkUserExists(String email) {
		Map<String, Object> resp = new HashMap<>();
		try {
			String normalised = email != null ? email.trim().toLowerCase() : "";
			resp.put("success", true);
			resp.put("exists", StringUtils.hasText(normalised) && userRepository.existsByEmail(normalised));
			resp.put("email", normalised);
		} catch (Exception e) {
			log.error("checkUserExists error: {}", e.getMessage());
			resp.put("success", false);
			resp.put("message", "Could not check email.");
		}
		return resp;
	}

	public Map<String, Object> getUserByEmail(String email) {
		Map<String, Object> resp = new HashMap<>();
		try {
			if (!StringUtils.hasText(email))
				return error("Email is required.");
			Optional<User> userOpt = userRepository.findByEmail(email.trim().toLowerCase());
			if (userOpt.isEmpty())
				return error("User not found.");
			resp.put("success", true);
			resp.put("user", UserResponseDTO.fromUser(userOpt.get()));
		} catch (Exception e) {
			log.error("getUserByEmail error: {}", e.getMessage());
			resp.put("success", false);
			resp.put("message", "Could not fetch user.");
		}
		return resp;
	}

	// ─────────────────────────────────────────────────────────────
	// Private helpers
	// ─────────────────────────────────────────────────────────────

	private Map<String, Object> error(String message) {
		Map<String, Object> r = new HashMap<>();
		r.put("success", false);
		r.put("message", message);
		return r;
	}

	/**
	 * Remove expired sessions from the temp map. Prevents unbounded growth if many
	 * users start registration but never finish. Also enforces a hard cap of
	 * MAX_TEMP_ENTRIES.
	 */
	private void evictExpiredSessions() {
		try {
			tempStorage.entrySet().removeIf(e -> e.getValue().isExpired());
			if (tempStorage.size() > MAX_TEMP_ENTRIES) {
				log.warn("⚠️ tempStorage exceeded {} entries — clearing all", MAX_TEMP_ENTRIES);
				tempStorage.clear();
			}
		} catch (Exception e) {
			log.error("evictExpiredSessions error: {}", e.getMessage());
		}
	}
}