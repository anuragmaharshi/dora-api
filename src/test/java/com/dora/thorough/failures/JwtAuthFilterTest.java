package com.dora.thorough.failures;

import com.dora.controllers.IncidentProbeController;
import com.dora.entities.AppRole;
import io.jsonwebtoken.JwtException;
import com.dora.entities.AppUser;
import com.dora.entities.Tenant;
import com.dora.repositories.AppUserRepository;
import com.dora.security.DevJwtService;
import com.dora.security.JwtAuthFilter;
import com.dora.security.RoleNames;
import com.dora.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Thorough tests for JwtAuthFilter using @WebMvcTest against IncidentProbeController.
 *
 * Uses the real IncidentProbeController endpoint (GET /api/v1/incidents/_probe)
 * so the security layer is exercised end-to-end without an inner test stub.
 *
 * Validates: happy-path security context population, missing header → 401,
 * bad signature → 401, expired token → 401, malformed bearer → 401,
 * inactive user → 401, and that the login path bypasses the filter entirely.
 */
@WebMvcTest(controllers = IncidentProbeController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
class JwtAuthFilterTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    DevJwtService jwtService;

    @MockBean
    AppUserRepository userRepository;

    private AppUser activeUser;
    private AppUser inactiveUser;

    private static final String GOOD_TOKEN = "good.token.here";
    private static final String BAD_SIG_TOKEN = "bad.signature.token";
    private static final String EXPIRED_TOKEN = "expired.token.here";

    @BeforeEach
    void setUp() {
        activeUser = buildUser("ops@dora.local", true, RoleNames.OPS_ANALYST);
        inactiveUser = buildUser("inactive@dora.local", false, RoleNames.OPS_ANALYST);
    }

    // ── AC-2: valid token → SecurityContext populated ─────────────────────────

    @Test
    @Tag("AC-2")
    @DisplayName("Valid bearer token: security context is populated and request succeeds with 200")
    void validBearerToken_populatesSecurityContextAndContinues() throws Exception {
        io.jsonwebtoken.Claims claims = buildClaims("ops@dora.local", List.of(RoleNames.OPS_ANALYST));
        when(jwtService.verify(GOOD_TOKEN)).thenReturn(claims);
        when(userRepository.findByEmail("ops@dora.local")).thenReturn(Optional.of(activeUser));

        mockMvc.perform(get("/api/v1/incidents/_probe")
                        .header("Authorization", "Bearer " + GOOD_TOKEN))
                .andExpect(status().isOk());
    }

    // ── AC-5: missing header → 401 ────────────────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("Missing Authorization header on protected endpoint returns 401")
    void missingAuthHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/incidents/_probe"))
                .andExpect(status().isUnauthorized());

        // Filter should not even attempt to verify a token
        verify(jwtService, never()).verify(anyString());
    }

    // ── AC-5: invalid signature → 401 ─────────────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("Bearer token with invalid signature: filter clears context → 401")
    void bearerWithInvalidSignature_returns401() throws Exception {
        when(jwtService.verify(BAD_SIG_TOKEN)).thenThrow(new JwtException("bad signature"));

        mockMvc.perform(get("/api/v1/incidents/_probe")
                        .header("Authorization", "Bearer " + BAD_SIG_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    // ── AC-5: expired token → 401 ─────────────────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("Bearer token expired beyond skew tolerance: filter clears context → 401")
    void expiredToken_returns401() throws Exception {
        when(jwtService.verify(EXPIRED_TOKEN))
                .thenThrow(new io.jsonwebtoken.ExpiredJwtException(null, null, "Token expired"));

        mockMvc.perform(get("/api/v1/incidents/_probe")
                        .header("Authorization", "Bearer " + EXPIRED_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    // ── AC-5: malformed bearer (no space after "Bearer") → 401 ───────────────

    @Test
    @Tag("AC-5")
    @DisplayName("Malformed Authorization header 'BearerToken' (no space) is ignored → 401")
    void malformedBearerNoSpace_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/incidents/_probe")
                        .header("Authorization", "BearerToken"))
                .andExpect(status().isUnauthorized());

        verify(jwtService, never()).verify(anyString());
    }

    @Test
    @Tag("AC-5")
    @DisplayName("Authorization header with value 'Bearer' (trailing space only) is ignored → 401")
    void bearerWithEmptyToken_returns401() throws Exception {
        // Header is "Bearer " — the token part is blank
        when(jwtService.verify("")).thenThrow(new JwtException("empty token"));

        mockMvc.perform(get("/api/v1/incidents/_probe")
                        .header("Authorization", "Bearer "))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Tag("AC-5")
    @DisplayName("Authorization header with 'Basic ...' scheme (not Bearer) is ignored → 401")
    void basicAuthHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/incidents/_probe")
                        .header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());

        verify(jwtService, never()).verify(anyString());
    }

    // ── AC-5: inactive user → 401 ─────────────────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("Valid JWT but user has active=false in DB: 401 (account disabled)")
    void validTokenButInactiveUser_returns401() throws Exception {
        // JWT is structurally valid and verifies cleanly,
        // but the loaded user entity has active=false.
        io.jsonwebtoken.Claims claims = buildClaims(
                "inactive@dora.local", List.of(RoleNames.OPS_ANALYST));
        when(jwtService.verify("inactive.token")).thenReturn(claims);
        when(userRepository.findByEmail("inactive@dora.local"))
                .thenReturn(Optional.of(inactiveUser));

        // CustomUserDetails.isEnabled() returns false → Spring Security rejects it
        mockMvc.perform(get("/api/v1/incidents/_probe")
                        .header("Authorization", "Bearer inactive.token"))
                .andExpect(status().isUnauthorized());
    }

    // ── Login path must NOT apply filter ─────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("/api/v1/auth/login path bypasses the JWT filter (shouldNotFilter returns true)")
    void loginPath_filterDoesNotApply_noTokenRequired() throws Exception {
        // Post to login without any Authorization header — filter must not run.
        // The request will still fail (no handler for this test controller),
        // but it should NOT produce a 401 from missing-token logic.
        // We verify jwtService.verify() is never called.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ops@dora.local\",\"password\":\"x\"}"));

        verify(jwtService, never()).verify(anyString());
    }

    @Test
    @Tag("AC-1")
    @DisplayName("/api/v1/auth/login with a bearer token: filter skips verification, still permit-all")
    void loginPath_withBearerToken_filterStillSkips() throws Exception {
        // Even if a caller sends a token to /login, shouldNotFilter must return true
        // so verify() is never called on that path.
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("Authorization", "Bearer " + GOOD_TOKEN)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ops@dora.local\",\"password\":\"x\"}"));

        verify(jwtService, never()).verify(anyString());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private io.jsonwebtoken.Claims buildClaims(String email, List<String> roles) {
        // Build real claims using JJWT so the filter's claims.getSubject() call works
        javax.crypto.SecretKey key =
                io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "test-secret-change-me-min-32-char-ok"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String compactJwt = io.jsonwebtoken.Jwts.builder()
                .subject(email)
                .claim("roles", roles)
                .claim("tenant_id", "00000000-0000-0000-0000-000000000001")
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 3_600_000))
                .signWith(key)
                .compact();
        return io.jsonwebtoken.Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(compactJwt)
                .getPayload();
    }

    private AppUser buildUser(String email, boolean active, String roleCode) {
        Tenant tenant = new Tenant("Nexus Bank", "NEXUSBANK0000000001");
        setField(tenant, "id", UUID.fromString("00000000-0000-0000-0000-000000000001"));

        AppUser user = newInstance(AppUser.class);
        setField(user, "id", UUID.randomUUID());
        setField(user, "email", email);
        setField(user, "username", email.split("@")[0]);
        setField(user, "passwordHash", "$2a$10$hash");
        setField(user, "active", active);
        setField(user, "mfaEnabled", false);
        setField(user, "tenant", tenant);
        setField(user, "roles", Set.of(new AppRole(roleCode, roleCode)));
        return user;
    }

    private static <T> T newInstance(Class<T> cls) {
        try {
            var ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Could not instantiate " + cls, e);
        }
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = findField(target.getClass(), fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Could not set field '" + fieldName + "'", e);
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> search = clazz;
        while (search != null) {
            for (Field f : search.getDeclaredFields()) {
                if (f.getName().equals(name)) return f;
            }
            search = search.getSuperclass();
        }
        throw new RuntimeException("Field not found: " + name);
    }
}
