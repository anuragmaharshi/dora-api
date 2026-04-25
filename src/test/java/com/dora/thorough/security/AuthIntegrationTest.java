package com.dora.thorough.security;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.dora.security.RoleNames.OPS_ANALYST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack integration tests for the auth flow.
 *
 * Uses @SpringBootTest + Testcontainers PostgreSQL so Flyway runs the complete
 * migration set and all seeded users are available.
 *
 * Covers:
 * - Full login → /me round-trip
 * - JWT issued at login is accepted by /me
 * - active=false blocks login with same generic 401
 * - POST /refresh returns a different token string
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AuthIntegrationTest {

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

    @Autowired
    JdbcTemplate jdbc;

    // ── AC-1: full login flow ─────────────────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("Full login flow: POST /auth/login with ops@dora.local returns 200 and valid JWT")
    void fullLoginFlow_returnsValidJwt() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("ops@dora.local", "ChangeMe!23"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("ops@dora.local"))
                .andReturn();

        LoginResponse resp = objectMapper.readValue(
                result.getResponse().getContentAsString(), LoginResponse.class);
        // JWT must be a 3-part compact token
        assertThat(resp.token().split("\\.")).hasSize(3);
    }

    // ── AC-2: use JWT from login to call /me ──────────────────────────────────

    @Test
    @Tag("AC-2")
    @DisplayName("JWT from login is accepted by GET /auth/me and returns OPS_ANALYST profile")
    void loginThenGetMe_returnsCorrectProfile() throws Exception {
        String token = loginAndGetToken("ops@dora.local", "ChangeMe!23");

        MvcResult meResult = mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        UserProfile profile = objectMapper.readValue(
                meResult.getResponse().getContentAsString(), UserProfile.class);

        assertThat(profile.email()).isEqualTo("ops@dora.local");
        assertThat(profile.roles()).containsExactly(OPS_ANALYST);
        assertThat(profile.tenantId()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(profile.mfaEnabled()).isFalse();
    }

    // ── AC-5: active=false blocks login ───────────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("Login succeeds for active user; after setting active=false via JDBC, login returns 401")
    void activeUserLogsIn_thenDeactivated_loginBlocked() throws Exception {
        // Step 1: login succeeds for active user
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("ops@dora.local", "ChangeMe!23"))))
                .andExpect(status().isOk());

        // Step 2: deactivate the user via direct JDBC (simulates an admin action)
        jdbc.execute("UPDATE app_user SET active = FALSE WHERE email = 'ops@dora.local'");

        try {
            // Step 3: login now returns 401 with generic message
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new LoginRequest("ops@dora.local", "ChangeMe!23"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid credentials"));
        } finally {
            // Restore so other tests are not affected (shared container)
            jdbc.execute("UPDATE app_user SET active = TRUE WHERE email = 'ops@dora.local'");
        }
    }

    @Test
    @Tag("AC-5")
    @DisplayName("Inactive user's error message is identical to wrong-password message (no enumeration)")
    void inactiveUserMessageMatchesWrongPasswordMessage() throws Exception {
        jdbc.execute("UPDATE app_user SET active = FALSE WHERE email = 'board@dora.local'");

        try {
            String inactiveMsg = extractLoginErrorMessage("board@dora.local", "ChangeMe!23");
            String wrongPwdMsg = extractLoginErrorMessage("compliance@dora.local", "WrongPassword!");

            assertThat(inactiveMsg).isEqualTo(wrongPwdMsg).isEqualTo("Invalid credentials");
        } finally {
            jdbc.execute("UPDATE app_user SET active = TRUE WHERE email = 'board@dora.local'");
        }
    }

    // ── AC-2: POST /refresh returns a new token ───────────────────────────────

    @Test
    @Tag("AC-2")
    @DisplayName("POST /refresh with valid token issues a new JWT (token strings are different)")
    void refresh_returnsNewTokenDifferentFromOriginal() throws Exception {
        String token1 = loginAndGetToken("compliance@dora.local", "ChangeMe!23");

        // Small sleep to ensure the new token's iat differs (tokens issued in same second
        // with same claims would be identical — this is expected JJWT behaviour).
        Thread.sleep(1100);

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        LoginResponse refreshResp = objectMapper.readValue(
                refreshResult.getResponse().getContentAsString(), LoginResponse.class);
        String token2 = refreshResp.token();

        assertThat(token2).isNotEqualTo(token1);
    }

    @Test
    @Tag("AC-2")
    @DisplayName("Refreshed token is accepted by GET /me (it is a real working JWT)")
    void refreshedToken_isAcceptedByMe() throws Exception {
        String original = loginAndGetToken("ciso@dora.local", "ChangeMe!23");
        Thread.sleep(1100);

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Authorization", "Bearer " + original))
                .andExpect(status().isOk())
                .andReturn();

        String refreshed = objectMapper.readValue(
                refreshResult.getResponse().getContentAsString(), LoginResponse.class).token();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + refreshed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ciso@dora.local"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String loginAndGetToken(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(
                result.getResponse().getContentAsString(), LoginResponse.class).token();
    }

    private String extractLoginErrorMessage(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("message").asText();
    }
}
