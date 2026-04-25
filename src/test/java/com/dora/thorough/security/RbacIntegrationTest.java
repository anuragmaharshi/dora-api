package com.dora.thorough.security;

import com.dora.dto.LoginRequest;
import com.dora.dto.LoginResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.stream.Stream;

import static com.dora.security.RoleNames.BOARD_VIEWER;
import static com.dora.security.RoleNames.CISO;
import static com.dora.security.RoleNames.COMPLIANCE_OFFICER;
import static com.dora.security.RoleNames.INCIDENT_MANAGER;
import static com.dora.security.RoleNames.OPS_ANALYST;
import static com.dora.security.RoleNames.PLATFORM_ADMIN;
import static com.dora.security.RoleNames.SYSTEM;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC integration tests covering all 7 seeded roles against GET /incidents/_probe.
 *
 * Per LLD-02 §11 role matrix and BR-011 / NFR-009:
 * - PLATFORM_ADMIN must receive 403 (excluded from @PreAuthorize allowlist)
 * - OPS_ANALYST, INCIDENT_MANAGER, COMPLIANCE_OFFICER, CISO, BOARD_VIEWER → 200
 * - SYSTEM → 403 (internal account not in @PreAuthorize list)
 * - Unauthenticated → 401 (not 403)
 *
 * All role assertions import RoleNames.* constants — never inline strings.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class RbacIntegrationTest {

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

    private static final String PROBE_PATH = "/api/v1/incidents/_probe";
    private static final String PASSWORD = "ChangeMe!23";

    // ── AC-4: PLATFORM_ADMIN blocked (BR-011, NFR-009) ────────────────────────

    @Test
    @Tag("AC-4")
    @DisplayName("PLATFORM_ADMIN role: GET /incidents/_probe returns 403 (BR-011 / NFR-009)")
    void platformAdmin_probeEndpoint_returns403() throws Exception {
        String token = loginAndGetToken("platform@dora.local", PASSWORD);

        mockMvc.perform(get(PROBE_PATH)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // ── AC-3/4: bank roles allowed ────────────────────────────────────────────

    @ParameterizedTest(name = "role={0}, email={1} → 200")
    @MethodSource("bankRoleUsers")
    @Tag("AC-3")
    @DisplayName("Bank roles: GET /incidents/_probe returns 200 (read access permitted)")
    void bankRoles_probeEndpoint_returns200(String role, String email) throws Exception {
        String token = loginAndGetToken(email, PASSWORD);

        mockMvc.perform(get(PROBE_PATH)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    static Stream<Arguments> bankRoleUsers() {
        return Stream.of(
                Arguments.of(OPS_ANALYST,        "ops@dora.local"),
                Arguments.of(INCIDENT_MANAGER,   "incident@dora.local"),
                Arguments.of(COMPLIANCE_OFFICER, "compliance@dora.local"),
                Arguments.of(CISO,               "ciso@dora.local"),
                Arguments.of(BOARD_VIEWER,       "board@dora.local")
        );
    }

    // ── AC-4: SYSTEM role blocked ──────────────────────────────────────────────

    @Test
    @Tag("AC-4")
    @DisplayName("SYSTEM role: GET /incidents/_probe returns 403 (SYSTEM not in @PreAuthorize allowlist)")
    void systemRole_probeEndpoint_returns403() throws Exception {
        String token = loginAndGetToken("system@dora.local", PASSWORD);

        mockMvc.perform(get(PROBE_PATH)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // ── AC-5: unauthenticated → 401 not 403 ──────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("Unauthenticated request to /incidents/_probe returns 401 (not 403)")
    void unauthenticated_probeEndpoint_returns401NotForbidden() throws Exception {
        mockMvc.perform(get(PROBE_PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Tag("AC-5")
    @DisplayName("Request with tampered token to /incidents/_probe returns 401 (invalid JWT)")
    void tamperedToken_probeEndpoint_returns401() throws Exception {
        String token = loginAndGetToken("ops@dora.local", PASSWORD);
        // Corrupt the signature
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "tampered";

        mockMvc.perform(get(PROBE_PATH)
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    // ── additional: all 7 roles login successfully ─────────────────────────────

    @ParameterizedTest(name = "role={0}, email={1} → login 200")
    @MethodSource("allRoleUsers")
    @Tag("AC-1")
    @DisplayName("All 7 seeded role accounts can log in (returns 200 with JWT)")
    void allSeededRoles_canLogin(String role, String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.roles[0]").value(role));
    }

    static Stream<Arguments> allRoleUsers() {
        return Stream.of(
                Arguments.of(PLATFORM_ADMIN,      "platform@dora.local"),
                Arguments.of(OPS_ANALYST,         "ops@dora.local"),
                Arguments.of(INCIDENT_MANAGER,    "incident@dora.local"),
                Arguments.of(COMPLIANCE_OFFICER,  "compliance@dora.local"),
                Arguments.of(CISO,                "ciso@dora.local"),
                Arguments.of(BOARD_VIEWER,        "board@dora.local"),
                Arguments.of(SYSTEM,              "system@dora.local")
        );
    }

    // ── AC-4: PLATFORM_ADMIN blocked, OPS_ANALYST allowed in same test ────────

    @Test
    @Tag("AC-4")
    @DisplayName("BR-011: PLATFORM_ADMIN blocked (403) and OPS_ANALYST allowed (200) in same run")
    void br011_platformAdminBlocked_opsAnalystAllowed() throws Exception {
        String platformToken = loginAndGetToken("platform@dora.local", PASSWORD);
        String opsToken = loginAndGetToken("ops@dora.local", PASSWORD);

        mockMvc.perform(get(PROBE_PATH)
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(PROBE_PATH)
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String loginAndGetToken(String email, String password) throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(
                result.getResponse().getContentAsString(), LoginResponse.class).token();
    }
}
