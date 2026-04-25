package com.dora.thorough.failures;

import com.dora.controllers.AuthController;
import com.dora.dto.ErrorResponse;
import com.dora.dto.LoginRequest;
import com.dora.dto.LoginResponse;
import com.dora.dto.UserProfile;
import com.dora.security.CustomUserDetails;
import com.dora.security.DevJwtService;
import com.dora.security.JwtAuthFilter;
import com.dora.security.RoleNames;
import com.dora.security.SecurityConfig;
import com.dora.services.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dora.entities.AppRole;
import com.dora.entities.AppUser;
import com.dora.entities.Tenant;
import com.dora.repositories.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Thorough WebMvcTest for AuthController.
 *
 * Validates: POST /login success/failure modes (wrong password, unknown user,
 * inactive user), GET /me happy-path and missing token, POST /logout, POST /refresh
 * returns a different token, no stack traces in error bodies.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    AuthService authService;

    @MockBean
    DevJwtService jwtService;

    @MockBean
    AppUserRepository userRepository;

    private static final String VALID_TOKEN = "valid.jwt.token";
    private static final String REFRESHED_TOKEN = "refreshed.jwt.token";
    private static final String OPS_EMAIL = "ops@dora.local";
    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";

    private UserProfile opsProfile;
    private LoginResponse loginResponse;
    private AppUser opsUser;

    @BeforeEach
    void setUp() {
        opsProfile = new UserProfile(OPS_EMAIL, List.of(RoleNames.OPS_ANALYST), TENANT_ID, false);
        loginResponse = new LoginResponse(VALID_TOKEN, Instant.now().plusSeconds(3600), opsProfile);
        opsUser = buildActiveUser(OPS_EMAIL, RoleNames.OPS_ANALYST);

        // Default: verify returns claims for the valid token so the filter lets requests through
        io.jsonwebtoken.Claims claims = buildClaims(OPS_EMAIL, List.of(RoleNames.OPS_ANALYST));
        when(jwtService.verify(VALID_TOKEN)).thenReturn(claims);
        when(jwtService.verify(REFRESHED_TOKEN)).thenReturn(claims);
        when(userRepository.findByEmail(OPS_EMAIL)).thenReturn(Optional.of(opsUser));
    }

    // ── POST /login success ────────────────────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("POST /login with valid credentials: 200, body has token, expiresAt, and user fields")
    void login_validCredentials_returns200WithAllFields() throws Exception {
        when(authService.login(OPS_EMAIL, "ChangeMe!23")).thenReturn(loginResponse);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(OPS_EMAIL, "ChangeMe!23"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(OPS_EMAIL))
                .andExpect(jsonPath("$.user.roles[0]").value(RoleNames.OPS_ANALYST))
                .andExpect(jsonPath("$.user.tenantId").value(TENANT_ID))
                .andExpect(jsonPath("$.user.mfaEnabled").value(false))
                .andReturn();

        LoginResponse resp = objectMapper.readValue(
                result.getResponse().getContentAsString(), LoginResponse.class);
        assertThat(resp.token()).isEqualTo(VALID_TOKEN);
    }

    // ── POST /login wrong password ─────────────────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("POST /login wrong password: 401, body has message field, no stack trace")
    void login_wrongPassword_returns401WithMessageNoStackTrace() throws Exception {
        when(authService.login(OPS_EMAIL, "WrongPass!")).thenThrow(
                new BadCredentialsException("Invalid credentials"));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(OPS_EMAIL, "WrongPass!"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("at com.dora");
        assertThat(body).doesNotContain("Exception");
        assertThat(body).doesNotContain("stack");
        assertThat(body).contains("timestamp");
    }

    // ── POST /login unknown email ──────────────────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("POST /login unknown email: 401, same generic message (prevents user enumeration)")
    void login_unknownEmail_returns401WithSameGenericMessage() throws Exception {
        when(authService.login("nobody@dora.local", "ChangeMe!23"))
                .thenThrow(new BadCredentialsException("Invalid credentials"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("nobody@dora.local", "ChangeMe!23"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    @Tag("AC-5")
    @DisplayName("POST /login unknown email and wrong password: messages are identical (no enumeration)")
    void login_unknownVsWrongPassword_sameMessage() throws Exception {
        when(authService.login(anyString(), anyString()))
                .thenThrow(new BadCredentialsException("Invalid credentials"));

        String unknownMsg = extractMessage("ghost@dora.local", "ChangeMe!23");
        String wrongPwdMsg = extractMessage(OPS_EMAIL, "WrongPass!");

        assertThat(unknownMsg).isEqualTo(wrongPwdMsg)
                .isEqualTo("Invalid credentials");
    }

    // ── POST /login inactive user ──────────────────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("POST /login inactive user: 401, same generic message (no account-status disclosure)")
    void login_inactiveUser_returns401WithGenericMessage() throws Exception {
        when(authService.login("inactive@dora.local", "ChangeMe!23"))
                .thenThrow(new BadCredentialsException("Invalid credentials"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("inactive@dora.local", "ChangeMe!23"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    // ── POST /login input validation ───────────────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("POST /login with blank email: 400 (Bean Validation)")
    void login_blankEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\"ChangeMe!23\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-5")
    @DisplayName("POST /login with invalid email format: 400 (Bean Validation)")
    void login_invalidEmailFormat_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"notanemail\",\"password\":\"ChangeMe!23\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-5")
    @DisplayName("POST /login with blank password: 400 (Bean Validation)")
    void login_blankPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ops@dora.local\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── GET /me ────────────────────────────────────────────────────────────────

    @Test
    @Tag("AC-2")
    @DisplayName("GET /me with valid bearer: 200, UserProfile with email, roles, tenantId, mfaEnabled")
    void getMe_validBearer_returnsFullUserProfile() throws Exception {
        when(authService.getUserProfile(OPS_EMAIL)).thenReturn(opsProfile);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(OPS_EMAIL))
                .andExpect(jsonPath("$.roles[0]").value(RoleNames.OPS_ANALYST))
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID))
                .andExpect(jsonPath("$.mfaEnabled").value(false));
    }

    @Test
    @Tag("AC-2")
    @DisplayName("GET /me without bearer token: 401")
    void getMe_noBearer_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /logout ───────────────────────────────────────────────────────────

    @Test
    @Tag("AC-2")
    @DisplayName("POST /logout with valid bearer: 204 No Content")
    void logout_validBearer_returns204() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isNoContent());
    }

    @Test
    @Tag("AC-2")
    @DisplayName("POST /logout without bearer: 401")
    void logout_noBearer_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /refresh ──────────────────────────────────────────────────────────

    @Test
    @Tag("AC-2")
    @DisplayName("POST /refresh with valid bearer: 200, new token is different from original")
    void refresh_validBearer_returnsNewDifferentToken() throws Exception {
        LoginResponse refreshedResponse = new LoginResponse(
                REFRESHED_TOKEN, Instant.now().plusSeconds(3600), opsProfile);
        when(authService.refresh(OPS_EMAIL)).thenReturn(refreshedResponse);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();

        LoginResponse resp = objectMapper.readValue(
                result.getResponse().getContentAsString(), LoginResponse.class);
        assertThat(resp.token()).isEqualTo(REFRESHED_TOKEN);
        assertThat(resp.token()).isNotEqualTo(VALID_TOKEN);
    }

    @Test
    @Tag("AC-2")
    @DisplayName("POST /refresh without bearer: 401")
    void refresh_noBearer_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String extractMessage(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andReturn();
        ErrorResponse err = objectMapper.readValue(
                result.getResponse().getContentAsString(), ErrorResponse.class);
        return err.message();
    }

    private io.jsonwebtoken.Claims buildClaims(String email, List<String> roles) {
        javax.crypto.SecretKey key =
                io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "test-secret-change-me-min-32-char-ok"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String compact = io.jsonwebtoken.Jwts.builder()
                .subject(email)
                .claim("roles", roles)
                .claim("tenant_id", TENANT_ID)
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 3_600_000))
                .signWith(key)
                .compact();
        return io.jsonwebtoken.Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(compact)
                .getPayload();
    }

    private AppUser buildActiveUser(String email, String roleCode) {
        Tenant tenant = new Tenant("Nexus Bank", "NEXUSBANK0000000001");
        setField(tenant, "id", UUID.fromString(TENANT_ID));

        AppUser user = newInstance(AppUser.class);
        setField(user, "id", UUID.randomUUID());
        setField(user, "email", email);
        setField(user, "username", email.split("@")[0]);
        setField(user, "passwordHash", "$2a$10$hash");
        setField(user, "active", true);
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
