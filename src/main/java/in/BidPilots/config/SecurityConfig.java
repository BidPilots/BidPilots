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

    @Value("${app.cors.allowed-origins:https://bidpilots-production.up.railway.app,http://localhost:8080}")
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

            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers("/", "/index.html").permitAll()
                .requestMatchers("/api/users/register-form", "/api/users/register", 
                    "/api/users/initiate-registration", "/api/users/verify-email-otp",
                    "/api/users/resend-email-otp", "/api/users/check-exists",
                    "/api/users/user/**", "/api/users/health").permitAll()
                .requestMatchers("/api/users/login-form", "/api/users/login", 
                    "/api/users/form-login").permitAll()
                .requestMatchers("/api/users/current-user").permitAll()
                .requestMatchers("/api/users/forgot-password", "/api/users/reset-password",
                    "/api/users/resend-password-otp", "/api/users/verify-reset-otp").permitAll()
                .requestMatchers("/**.html", "/css/**", "/js/**", "/images/**").permitAll()
                .requestMatchers("/api/subscriptions/plans").permitAll()
                .requestMatchers("/payment/callback", "/api/subscriptions/webhook").permitAll()
                .requestMatchers("/api/subscriptions/login").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                
                // Authenticated endpoints
                .requestMatchers("/api/user/**").hasRole("USER")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )

            .logout(logout -> logout
                .logoutUrl("/api/users/logout")
                .logoutSuccessUrl("/api/users/login-form?logout=true")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID", "remember-me")
                .permitAll()
            )

            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .maximumSessions(1)
                    .expiredUrl("/api/users/login-form?expired=true")
                    .maxSessionsPreventsLogin(false)
            )

            .rememberMe(remember -> remember
                .key("uniqueAndSecret")
                .tokenValiditySeconds(86400)
                .rememberMeCookieName("remember-me")
                .useSecureCookie(true)
                .rememberMeParameter("remember-me")
            );

        return http.build();
    }
}
