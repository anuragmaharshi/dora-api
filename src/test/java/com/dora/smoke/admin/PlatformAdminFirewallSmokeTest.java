package com.dora.smoke.admin;

import com.dora.dto.LoginRequest;
import com.dora.dto.LoginResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-6 and AC-7 smoke tests for PlatformAdminFirewallFilter.
 *
 * <p>AC-6: PLATFORM_ADMIN is blocked (403) when accessing non-admin paths
 * (BR-011, NFR-009, LLD-04 §2). The firewall records a PLATFORM_ADMIN_BLOCKED_PATH
 * audit event.
 *
 * <p>AC-7: Bank roles (OPS_ANALYST) are blocked (403) from admin endpoints
 * by @PreAuthorize("hasRole('PLATFORM_ADMIN')").
 *
 * <p>Uses @SpringBootTest with real JWT tokens so that the full filter chain runs,
 * including PlatformAdminFirewallFilter. @WebMvcTest would need additional mocking
 * that would under-test the security configuration.
 */
@Tag("AC-6")
@DisplayName("AC-6/AC-7: PlatformAdminFirewallFilter and role separation")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class PlatformAdminFirewallSmokeTest {

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

    private String platformToken;
    private String opsToken;

    @BeforeEach
    void obtainTokens() throws Exception {
        platformToken = loginAndGetToken("platform@dora.local", "ChangeMe!23");
        opsToken = loginAndGetToken("ops@dora.local", "ChangeMe!23");
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: PLATFORM_ADMIN GET /api/v1/audit returns 403 (firewall blocks non-admin path)")
    void platformAdmin_getAudit_returns403() throws Exception {
        // The audit endpoint is NOT in the firewall whitelist → must return 403
        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", "00000000-0000-0000-0000-000000000001")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: PLATFORM_ADMIN can access /api/v1/admin/ paths (whitelist allows)")
    void platformAdmin_getAdminTenant_isNotBlockedByFirewall() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: OPS_ANALYST GET /api/v1/admin/tenant returns 403 (missing PLATFORM_ADMIN role)")
    void opsAnalyst_getAdminTenant_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: OPS_ANALYST POST /api/v1/admin/critical-services returns 403")
    void opsAnalyst_postCriticalService_returns403() throws Exception {
        String body = """
                { "name": "ShouldBeBlocked", "description": "x" }
                """;
        mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: PLATFORM_ADMIN accessing /actuator/health is allowed (whitelisted)")
    void platformAdmin_actuatorHealth_isAllowed() throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk());
    }

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
}
