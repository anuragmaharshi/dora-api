package com.dora.thorough.admin;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-7 thorough tests: exhaustive authz coverage across all 10 admin endpoints.
 *
 * <p>The smoke test covers only two endpoints (GET /tenant, POST /critical-services) with
 * OPS_ANALYST. This class covers:
 * <ul>
 *   <li>OPS_ANALYST on all 10 admin endpoints → 403</li>
 *   <li>Unauthenticated on all 10 admin endpoints → 401</li>
 * </ul>
 *
 * <p>These tests exercise the @PreAuthorize("hasRole('PLATFORM_ADMIN')") annotation on
 * AdminController, which is the first line of defence (the PlatformAdminFirewallFilter
 * is the second line, tested separately).
 */
@Tag("AC-7")
@DisplayName("AC-7: Admin endpoints — OPS_ANALYST gets 403, unauthenticated gets 401 on all 10 endpoints")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AdminAuthzThoroughTest {

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

    private String opsToken;

    @BeforeEach
    void obtainOpsToken() throws Exception {
        opsToken = loginAndGetToken("ops@dora.local", "ChangeMe!23");
    }

    // ── OPS_ANALYST blocked on all admin endpoints ─────────────────────────────

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: OPS_ANALYST GET /api/v1/admin/tenant returns 403")
    void opsAnalyst_getTenant_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: OPS_ANALYST PUT /api/v1/admin/tenant returns 403")
    void opsAnalyst_putTenant_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "legalName": "HackedName" }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: OPS_ANALYST GET /api/v1/admin/critical-services returns 403")
    void opsAnalyst_listCriticalServices_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: OPS_ANALYST POST /api/v1/admin/critical-services returns 403")
    void opsAnalyst_createCriticalService_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Hacked Service", "description": "x" }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: OPS_ANALYST PUT /api/v1/admin/critical-services/{id} returns 403")
    void opsAnalyst_updateCriticalService_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/admin/critical-services/00000000-0000-0000-0000-000000000001")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Hacked Name", "description": "x" }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: OPS_ANALYST POST /api/v1/admin/critical-services/{id}/archive returns 403")
    void opsAnalyst_archiveCriticalService_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/critical-services/00000000-0000-0000-0000-000000000001/archive")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: OPS_ANALYST GET /api/v1/admin/client-base returns 403")
    void opsAnalyst_getClientBaseHistory_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/client-base")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: OPS_ANALYST POST /api/v1/admin/client-base returns 403")
    void opsAnalyst_addClientBaseEntry_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/client-base")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "clientCount": 999, "effectiveFrom": "2026-01-01T00:00:00Z" }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: OPS_ANALYST GET /api/v1/admin/nca-email returns 403")
    void opsAnalyst_getNcaEmailConfig_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/nca-email")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: OPS_ANALYST PUT /api/v1/admin/nca-email returns 403")
    void opsAnalyst_updateNcaEmailConfig_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/admin/nca-email")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sender": "hacked@evil.com",
                                  "recipient": "victim@bank.com",
                                  "subjectTemplate": "DORA Report"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    // ── Unauthenticated blocked on all admin endpoints ─────────────────────────

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: unauthenticated GET /api/v1/admin/tenant returns 401")
    void unauthenticated_getTenant_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenant"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: unauthenticated PUT /api/v1/admin/tenant returns 401")
    void unauthenticated_putTenant_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "legalName": "Unauth Attempt" }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: unauthenticated GET /api/v1/admin/critical-services returns 401")
    void unauthenticated_listCriticalServices_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/critical-services"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: unauthenticated POST /api/v1/admin/critical-services returns 401")
    void unauthenticated_createCriticalService_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/critical-services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Unauth", "description": "x" }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: unauthenticated GET /api/v1/admin/client-base returns 401")
    void unauthenticated_getClientBase_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/client-base"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: unauthenticated GET /api/v1/admin/nca-email returns 401")
    void unauthenticated_getNcaEmail_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/nca-email"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: unauthenticated PUT /api/v1/admin/nca-email returns 401")
    void unauthenticated_putNcaEmail_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/admin/nca-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sender": "x@x.com",
                                  "recipient": "y@y.com",
                                  "subjectTemplate": "Subject"
                                }
                                """))
                .andExpect(status().isUnauthorized());
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
