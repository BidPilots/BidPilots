package in.BidPilots.config;

import in.BidPilots.entity.User;
import in.BidPilots.repository.UserLoginRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserLoginRepository userLoginRepository;

    /**
     * Allowed origins from application.properties.
     * Default covers localhost dev and the LAN IP configured in application.properties.
     */
    @Value("${app.cors.allowed-origins:http://localhost:8080,http://10.152.10.210:8080}")
    private String[] allowedOrigins;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return email -> {
            User user = userLoginRepository.findByEmail(email)
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
            return org.springframework.security.core.userdetails.User.builder()
                    .username(user.getEmail())
                    .password(user.getPassword())
                    .roles(user.getRole())
                    .build();
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(allowedOrigins));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())

            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    String accept      = request.getHeader("Accept");
                    String xRequested  = request.getHeader("X-Requested-With");
                    boolean isApiRequest =
                            (accept != null && accept.contains("application/json"))
                            || "XMLHttpRequest".equals(xRequested);
                    if (isApiRequest) {
                        response.sendError(
                            jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED,
                            "Unauthorized");
                    } else {
                        response.sendRedirect("/api/users/login-form?redirect="
                            + java.net.URLEncoder.encode(
                                request.getRequestURI(),
                                java.nio.charset.StandardCharsets.UTF_8));
                    }
                })
            )

            .authorizeHttpRequests(auth -> auth

                // ── Public root ──────────────────────────────────────────────
                .requestMatchers("/", "/index.html").permitAll()

                // ── Registration ─────────────────────────────────────────────
                .requestMatchers(
                    "/api/users/register-form",
                    "/api/users/register",
                    "/api/users/initiate-registration",
                    "/api/users/verify-email-otp",
                    "/api/users/resend-email-otp",
                    "/api/users/check-exists",
                    "/api/users/user/**",
                    "/api/users/health"
                ).permitAll()

                // ── Login ────────────────────────────────────────────────────
                .requestMatchers(
                    "/api/users/login-form",
                    "/api/users/login",
                    "/api/users/form-login"
                ).permitAll()

                // ── FIX: /api/users/current-user is used by subscription-plans.html
                //    to verify auth state client-side (returns JSON, never redirects).
                //    Must be public so an unauthenticated fetch returns a proper JSON
                //    response rather than an HTML redirect to the login page.
                .requestMatchers("/api/users/current-user").permitAll()

                // ── Forgot / reset password ───────────────────────────────────
                .requestMatchers(
                    "/api/users/forgot-password",
                    "/api/users/reset-password",
                    "/api/users/resend-password-otp",
                    "/api/users/verify-reset-otp"
                ).permitAll()

                // ── Static resources ─────────────────────────────────────────
                .requestMatchers(
                    "/register", "/login-form",
                    "/**.html", "/css/**", "/js/**", "/images/**",
                    "/images/logo.svg", "/images/white-logo.svg",
                    "/favicon.ico", "/logo.svg", "/white-logo.svg"
                ).permitAll()

                // ── Subscription & payment ────────────────────────────────────
                .requestMatchers("/api/subscriptions/plans").permitAll()
                .requestMatchers("/subscription/plans").authenticated()
                .requestMatchers(
                    "/payment/callback",
                    "/api/subscriptions/webhook"
                ).permitAll()
                .requestMatchers("/api/subscriptions/login").permitAll()
                .requestMatchers(
                    "/api/subscriptions/my-subscription",
                    "/api/subscriptions/create-order",
                    "/api/subscriptions/verify-payment",
                    "/api/subscriptions/cancel"
                ).authenticated()

                // ── Scraping — admin-only except health/stats ─────────────────
                .requestMatchers(
                    "/api/scrape/health",
                    "/api/scrape/progress",
                    "/api/scrape/stats",
                    "/api/scrape/dashboard"
                ).permitAll()
                .requestMatchers("/api/scrape/**").hasRole("ADMIN")

                // ── State/city extraction ─────────────────────────────────────
                .requestMatchers(HttpMethod.POST,   "/api/states-cities/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/states-cities/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET,    "/api/states-cities/**").permitAll()

                // ── Categories ───────────────────────────────────────────────
                .requestMatchers("/api/categories/scrape").hasRole("ADMIN")
                .requestMatchers("/api/categories/scrape/progress").permitAll()
                .requestMatchers(HttpMethod.GET,    "/api/categories/**").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/api/categories/**").hasRole("ADMIN")

                // ── User dashboard & bids ─────────────────────────────────────
                .requestMatchers("/api/user/dashboard").hasRole("USER")
                .requestMatchers("/api/user/my-dashboard").hasRole("USER")
                .requestMatchers("/api/user/bids/**").hasRole("USER")
                .requestMatchers("/api/user/**").hasRole("USER")

                // ── Admin dashboard ───────────────────────────────────────────
                .requestMatchers("/api/admin/dashboard").hasRole("ADMIN")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")

                // ── CORS preflight ────────────────────────────────────────────
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                .anyRequest().authenticated()
            )

            // ── Logout ───────────────────────────────────────────────────────
            .logout(logout -> logout
                .logoutUrl("/api/users/logout")
                .logoutSuccessUrl("/api/users/login-form?logout=true")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID", "remember-me")
                .permitAll()
            )

            // ── Session management — 24-hour session ─────────────────────────
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .maximumSessions(1)
                    .expiredUrl("/api/users/login-form?expired=true")
                    .maxSessionsPreventsLogin(false)
            )

            // ── Remember-me — 24 hours ───────────────────────────────────────
            .rememberMe(remember -> remember
                .key("uniqueAndSecret")
                .tokenValiditySeconds(86400)
                .rememberMeCookieName("remember-me")
                .useSecureCookie(false)
                .rememberMeParameter("remember-me")
            );

        return http.build();
    }
}