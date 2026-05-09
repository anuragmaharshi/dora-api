package com.dora.incidents.smoke;

import com.dora.dto.LoginRequest;
import com.dora.dto.LoginResponse;
import com.dora.incidents.application.ObjectStorageClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
 * Bug #22 regression test: admin-only endpoints must return 403 (not 401) for
 * authenticated users with the wrong role (e.g. OPS_ANALYST hitting /api/v1/admin/**).
 *
 * <p>Root cause: {@code SecurityConfig.exceptionHandling} only configured
 * {@code authenticationEntryPoint} (401 for unauthenticated). Without an explicit
 * {@code accessDeniedHandler}, certain filter-chain orderings caused Spring Security's
 * {@code ExceptionTranslationFilter} to route {@code AccessDeniedException} through the
 * entry point rather than the access-denied handler, producing 401 instead of 403.
 * Fix: added {@code .accessDeniedHandler(new AccessDeniedHandlerImpl())} to the
 * {@code exceptionHandling} DSL block.
 *
 * <p>This test also confirms that PLATFORM_ADMIN CAN access admin endpoints (200/201/204)
 * as a guard against over-blocking.
 */
@Tag("bug-22")
@DisplayName("Bug #22: BANK_USER hitting admin endpoints must receive 403, not 401")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AdminRoleGuardSmokeTest {

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
        registry.add("aws.s3.endpoint", () -> "http://localhost:9000");
        registry.add("aws.s3.access-key", () -> "minioadmin");
        registry.add("aws.s3.secret-key", () -> "minioadmin");
        registry.add("aws.s3.bucket", () -> "dora-local");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    ObjectStorageClient objectStorageClient;

    private String opsToken;
    private String platformToken;

    @BeforeEach
    void obtainTokens() throws Exception {
        opsToken = loginAndGetToken("ops@dora.local", "ChangeMe!23");
        platformToken = loginAndGetToken("platform@dora.local", "ChangeMe!23");
    }

    /**
     * Bug #22: OPS_ANALYST GET /api/v1/admin/tenant must return 403.
     * Previously returned 401 due to missing accessDeniedHandler in SecurityConfig.
     */
    @Test
    @Tag("bug-22")
    @DisplayName("Bug #22: OPS_ANALYST GET /admin/tenant returns 403")
    void opsAnalyst_getTenantConfig_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden());
    }

    /**
     * Bug #22: OPS_ANALYST GET /api/v1/admin/critical-services must return 403.
     */
    @Test
    @Tag("bug-22")
    @DisplayName("Bug #22: OPS_ANALYST GET /admin/critical-services returns 403")
    void opsAnalyst_listCriticalServices_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden());
    }

    /**
     * Bug #22: OPS_ANALYST GET /api/v1/admin/client-base must return 403.
     */
    @Test
    @Tag("bug-22")
    @DisplayName("Bug #22: OPS_ANALYST GET /admin/client-base returns 403")
    void opsAnalyst_getClientBase_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/client-base")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden());
    }

    /**
     * Bug #22: OPS_ANALYST GET /api/v1/admin/nca-email must return 403.
     */
    @Test
    @Tag("bug-22")
    @DisplayName("Bug #22: OPS_ANALYST GET /admin/nca-email returns 403")
    void opsAnalyst_getNcaEmail_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/nca-email")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden());
    }

    /**
     * Positive control: PLATFORM_ADMIN GET /api/v1/admin/critical-services must return 200.
     * Guards against over-blocking — the fix must not revoke legitimate admin access.
     */
    @Test
    @Tag("bug-22")
    @DisplayName("Bug #22: PLATFORM_ADMIN GET /admin/critical-services still returns 200")
    void platformAdmin_listCriticalServices_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk());
    }

    /**
     * Unauthenticated request to admin endpoint must return 401 (not 403).
     * Ensures the entry-point and access-denied-handler are both wired correctly.
     */
    @Test
    @Tag("bug-22")
    @DisplayName("Bug #22: unauthenticated GET /admin/tenant returns 401")
    void unauthenticated_getTenantConfig_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenant"))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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
