package com.dora.smoke;

import com.dora.dto.LoginRequest;
import com.dora.dto.LoginResponse;
import com.dora.dto.UserProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Happy-path smoke tests for LLD-02 acceptance criteria.
 *
 * Uses @SpringBootTest + Testcontainers Postgres so Flyway runs the full migration
 * set (V1_1_0 + V1_1_1) and the seeded users are available.
 *
 * Seeded credentials:
 *   ops@dora.local / ChangeMe!23       → OPS_ANALYST
 *   platform@dora.local / ChangeMe!23  → PLATFORM_ADMIN
 *   compliance@dora.local / ChangeMe!23 → COMPLIANCE_OFFICER
 *
 * Thorough tests (tamper detection, clock skew, account lockout) are in
 * src/test/java/com/dora/thorough/ — owned by the Java Unit Test agent (W4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AuthSmokeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("dora")
            .withUsername("dora")
            .withPassword("dora");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    // ── helpers ────────────────────────────────────────────────────────────────

    private String loginAndGetToken(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();

        LoginResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), LoginResponse.class);
        return response.token();
    }

    // ── AC-1 ───────────────────────────────────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: POST /auth/login with valid credentials returns 200 and a JWT with correct claims")
    void login_validCredentials_returns200WithJwt() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("compliance@dora.local", "ChangeMe!23"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("compliance@dora.local"))
                .andExpect(jsonPath("$.user.roles[0]").value("COMPLIANCE_OFFICER"))
                .andExpect(jsonPath("$.user.tenantId").isNotEmpty())
                .andReturn();

        LoginResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), LoginResponse.class);

        // JWT is a 3-part base64url structure
        assertThat(response.token().split("\\.")).hasSize(3);
        assertThat(response.user().roles()).contains("COMPLIANCE_OFFICER");
        // tenantId must be the seeded Nexus Bank tenant
        assertThat(response.user().tenantId()).isEqualTo("00000000-0000-0000-0000-000000000001");
    }

    // ── AC-2 ───────────────────────────────────────────────────────────────────

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: GET /auth/me with valid bearer returns authenticated user's profile")
    void getMe_withValidBearer_returnsUserProfile() throws Exception {
        String token = loginAndGetToken("ops@dora.local", "ChangeMe!23");

        MvcResult result = mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        UserProfile profile = objectMapper.readValue(
                result.getResponse().getContentAsString(), UserProfile.class);

        assertThat(profile.email()).isEqualTo("ops@dora.local");
        assertThat(profile.roles()).contains("OPS_ANALYST");
        assertThat(profile.tenantId()).isEqualTo("00000000-0000-0000-0000-000000000001");
    }

    // ── AC-3 ───────────────────────────────────────────────────────────────────

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: GET /auth/me without bearer token returns 401")
    void getMe_withoutBearer_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    // ── AC-4 ───────────────────────────────────────────────────────────────────

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: PLATFORM_ADMIN is blocked (403) from incident endpoint; OPS_ANALYST is allowed (200)")
    void incidentProbe_platformAdminBlocked_opsAnalystAllowed() throws Exception {
        String platformToken = loginAndGetToken("platform@dora.local", "ChangeMe!23");
        String opsToken = loginAndGetToken("ops@dora.local", "ChangeMe!23");

        // PLATFORM_ADMIN must get 403 — enforces BR-011 / NFR-009
        mockMvc.perform(get("/api/v1/incidents/_probe")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isForbidden());

        // OPS_ANALYST must get 200
        mockMvc.perform(get("/api/v1/incidents/_probe")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    // ── AC-5 ───────────────────────────────────────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: POST /auth/login with wrong password returns 401 with generic message (no stack trace)")
    void login_wrongPassword_returns401WithGenericMessage() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("ops@dora.local", "WrongPassword!"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // No stack trace in the response body
        assertThat(body).doesNotContain("at com.dora");
        assertThat(body).doesNotContain("Exception");
        // timestamp must be present
        assertThat(body).contains("timestamp");
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: POST /auth/login with non-existent user returns 401 with same generic message")
    void login_unknownUser_returns401WithGenericMessage() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("nobody@dora.local", "ChangeMe!23"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    // ── AC-7 ───────────────────────────────────────────────────────────────────

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: UserProfile always contains mfaEnabled field (even when false)")
    void userProfile_alwaysContainsMfaEnabledField() throws Exception {
        String token = loginAndGetToken("ops@dora.local", "ChangeMe!23");

        // mfa_enabled is false for seeded users — the field must still appear in JSON
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").exists())
                .andExpect(jsonPath("$.mfaEnabled").value(false));
    }

    // ── bonus: health remains permit-all ──────────────────────────────────────

    @Test
    @DisplayName("Health endpoint remains permit-all after SecurityConfig replacement")
    void healthEndpoint_remainsPermitAll() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("healthy"));
    }

    // ── POST /refresh ─────────────────────────────────────────────────────────

    @Test
    @Tag("AC-2")
    @DisplayName("POST /auth/refresh with valid token issues a new JWT")
    void refresh_withValidToken_returnsNewJwt() throws Exception {
        String token = loginAndGetToken("ops@dora.local", "ChangeMe!23");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    // ── POST /logout ──────────────────────────────────────────────────────────

    @Test
    @Tag("AC-2")
    @DisplayName("POST /auth/logout with valid token returns 204")
    void logout_withValidToken_returns204() throws Exception {
        String token = loginAndGetToken("ops@dora.local", "ChangeMe!23");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }
}
