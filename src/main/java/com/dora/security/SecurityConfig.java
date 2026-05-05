package com.dora.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Replaces the permissive LLD-01 config.
 *
 * Key decisions:
 * - STATELESS session: JWT carries all auth state; no server-side session storage.
 * - CSRF disabled: stateless APIs have no CSRF surface.
 * - BCryptPasswordEncoder at strength 10 per LLD-02 §9.
 * - 401 entry point returns HTTP 401 (not the default 302 redirect to a login page).
 * - @EnableMethodSecurity enables @PreAuthorize on service and controller methods.
 * - LLD-04: PlatformAdminFirewallFilter is registered AFTER JwtAuthFilter so it can
 *   read the populated Security context and block PLATFORM_ADMIN on non-admin paths.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final PlatformAdminFirewallFilter platformAdminFirewallFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          PlatformAdminFirewallFilter platformAdminFirewallFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.platformAdminFirewallFilter = platformAdminFirewallFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex ->
                    // Return 401 JSON-friendly response; avoid the default redirect to /login
                    ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(
                            "/api/v1/auth/login",
                            "/actuator/health",
                            "/actuator/health/**",
                            "/api/v1/health",
                            "/swagger-ui/**",
                            "/swagger-ui.html",
                            "/v3/api-docs/**",
                            "/openapi.yaml").permitAll()
                    .anyRequest().authenticated())
            // JwtAuthFilter populates the Security context; PlatformAdminFirewallFilter reads it.
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(platformAdminFirewallFilter, JwtAuthFilter.class);

        return http.build();
    }

    /**
     * Allows the Angular dev server (localhost:4200) to call the API directly.
     * In production (LLD-16) the frontend is served from the same origin or a
     * known CDN — update allowedOrigins accordingly.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:4200"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * BCrypt at strength 10 — OWASP minimum recommendation for new systems.
     * Increasing cost is a configuration change; no code change required.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * Exposes the AuthenticationManager so AuthService (and future OAuth2 config)
     * can delegate credential verification to Spring Security's provider chain.
     */
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }
}
