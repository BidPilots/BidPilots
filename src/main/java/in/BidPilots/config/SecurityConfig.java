package in.BidPilots.config;

import in.BidPilots.entity.User;
import in.BidPilots.repository.UserLoginRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private final UserLoginRepository userLoginRepository;

	@Bean
	public BCryptPasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
		return authConfig.getAuthenticationManager();
	}

	@Bean
	public UserDetailsService userDetailsService() {
		return email -> {
			User user = userLoginRepository.findByEmail(email)
					.orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
			return org.springframework.security.core.userdetails.User.builder().username(user.getEmail())
					.password(user.getPassword()).roles(user.getRole()).build();
		};
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(Arrays.asList("http://localhost:8080", "http://10.152.10.210:8080"));
		configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(Arrays.asList("*"));
		configuration.setAllowCredentials(true);
		configuration.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http.cors(cors -> cors.configurationSource(corsConfigurationSource())).csrf(csrf -> csrf.disable())
				.authorizeHttpRequests(auth -> auth

						// ═══════════════════════════════════════════════════════════════════
						// PUBLIC ROOT / INDEX.HTML
						// ═══════════════════════════════════════════════════════════════════
						.requestMatchers("/").permitAll().requestMatchers("/index.html").permitAll()

						// ═══════════════════════════════════════════════════════════════════
						// PUBLIC REGISTRATION
						// ═══════════════════════════════════════════════════════════════════
						.requestMatchers("/api/users/register-form").permitAll().requestMatchers("/api/users/register")
						.permitAll().requestMatchers("/api/users/initiate-registration").permitAll()
						.requestMatchers("/api/users/verify-email-otp").permitAll()
						.requestMatchers("/api/users/resend-email-otp").permitAll()
						.requestMatchers("/api/users/check-exists").permitAll().requestMatchers("/api/users/user/**")
						.permitAll().requestMatchers("/api/users/health").permitAll()

						// ═══════════════════════════════════════════════════════════════════
						// PUBLIC LOGIN
						// ═══════════════════════════════════════════════════════════════════
						.requestMatchers("/api/users/login-form").permitAll().requestMatchers("/api/users/login")
						.permitAll().requestMatchers("/api/users/form-login").permitAll()

						// ═══════════════════════════════════════════════════════════════════
						// PUBLIC FORGOT PASSWORD ENDPOINTS
						// ═══════════════════════════════════════════════════════════════════
						.requestMatchers("/api/users/forgot-password").permitAll()
						.requestMatchers("/api/users/reset-password").permitAll()
						.requestMatchers("/api/users/resend-password-otp").permitAll()
						.requestMatchers("/api/users/verify-reset-otp").permitAll()

						// ═══════════════════════════════════════════════════════════════════
						// STATIC RESOURCES
						// ═══════════════════════════════════════════════════════════════════
						.requestMatchers("/register").permitAll().requestMatchers("/login-form").permitAll()
						.requestMatchers("/**.html").permitAll().requestMatchers("/css/**").permitAll()
						.requestMatchers("/js/**").permitAll().requestMatchers("/images/**").permitAll()
						.requestMatchers("/images/logo.svg").permitAll().requestMatchers("/images/white-logo.svg")
						.permitAll().requestMatchers("/favicon.ico").permitAll().requestMatchers("/logo.svg")
						.permitAll().requestMatchers("/white-logo.svg").permitAll()

						// ═══════════════════════════════════════════════════════════════════
						// SUBSCRIPTION PLANS & PAYMENT (PUBLIC)
						// ═══════════════════════════════════════════════════════════════════
						.requestMatchers("/subscription/plans").permitAll().requestMatchers("/api/subscriptions/plans")
						.permitAll().requestMatchers("/payment/callback").permitAll()
						.requestMatchers("/api/subscriptions/webhook").permitAll()
						.requestMatchers("/api/subscriptions/login").permitAll() // Allow expired users to login

						// ═══════════════════════════════════════════════════════════════════
						// PUBLIC SCRAPING ENDPOINTS
						// ═══════════════════════════════════════════════════════════════════
						.requestMatchers("/api/scrape/health").permitAll().requestMatchers("/api/scrape/progress")
						.permitAll().requestMatchers("/api/scrape/stats").permitAll()
						.requestMatchers("/api/scrape/dashboard").permitAll()

						// ═══════════════════════════════════════════════════════════════════
						// USER DASHBOARD & BIDS (AUTHENTICATED)
						// ═══════════════════════════════════════════════════════════════════
						.requestMatchers("/api/user/dashboard").hasRole("USER")
						.requestMatchers("/api/user/my-dashboard").hasRole("USER").requestMatchers("/api/user/bids/**")
						.hasRole("USER").requestMatchers("/api/user/**").hasRole("USER")

						// ═══════════════════════════════════════════════════════════════════
						// CATEGORIES
						// ═══════════════════════════════════════════════════════════════════
						.requestMatchers("/api/categories/scrape").hasRole("ADMIN")
						.requestMatchers("/api/categories/scrape/progress").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/categories/**").permitAll()
						.requestMatchers(HttpMethod.DELETE, "/api/categories/**").hasRole("ADMIN")

						// ═══════════════════════════════════════════════════════════════════
						// ADMIN DASHBOARD
						// ═══════════════════════════════════════════════════════════════════
						.requestMatchers("/api/admin/dashboard").hasRole("ADMIN").requestMatchers("/api/admin/**")
						.hasRole("ADMIN")

						// ═══════════════════════════════════════════════════════════════════
						// CORS PREFLIGHT
						// ═══════════════════════════════════════════════════════════════════
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

						.anyRequest().authenticated())
				// ═══════════════════════════════════════════════════════════════════════════
				// LOGOUT CONFIGURATION
				// ═══════════════════════════════════════════════════════════════════════════
				.logout(logout -> logout.logoutUrl("/api/users/logout")
						.logoutSuccessUrl("/api/users/login-form?logout=true").invalidateHttpSession(true)
						.deleteCookies("JSESSIONID", "remember-me") // Delete both cookies on logout
						.permitAll())
				// ═══════════════════════════════════════════════════════════════════════════
				// SESSION MANAGEMENT — 24 HOUR SESSION
				// ═══════════════════════════════════════════════════════════════════════════
				.sessionManagement(
						session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED).maximumSessions(1)
								.expiredUrl("/api/users/login-form?expired=true").maxSessionsPreventsLogin(false) // Allow
																													// new
																													// login,
																													// invalidate
																													// old
																													// session
				)
				// ═══════════════════════════════════════════════════════════════════════════
				// REMEMBER-ME — 24 HOUR COOKIE
				// ═══════════════════════════════════════════════════════════════════════════
				.rememberMe(remember -> remember.key("uniqueAndSecret") // Secret key for encryption
						.tokenValiditySeconds(86400) // 24 hours (86400 seconds)
						.rememberMeCookieName("remember-me") // Cookie name
						.useSecureCookie(false) // Set to true for HTTPS
						.rememberMeParameter("remember-me") // Parameter name from login form
				);

		return http.build();
	}
}