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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-1 smoke tests: PLATFORM_ADMIN can GET and PUT /api/v1/admin/tenant.
 * OPS_ANALYST is blocked with 403.
 */
@Tag("AC-1")
@DisplayName("AC-1: Tenant Config — GET/PUT /api/v1/admin/tenant")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AdminTenantSmokeTest {

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
    @Tag("AC-1")
    @DisplayName("AC-1: PLATFORM_ADMIN GET /api/v1/admin/tenant returns 200 with tenant fields")
    void platformAdmin_getTenant_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.legalName").isNotEmpty());
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: PLATFORM_ADMIN PUT /api/v1/admin/tenant updates and returns updated config")
    void platformAdmin_putTenant_returns200WithUpdatedConfig() throws Exception {
        // Use inline JSON to avoid Map import and ensure field order is deterministic
        String body = """
                {
                  "legalName": "Nexus Bank Updated",
                  "lei": "NEXUSBNK000000000001",
                  "ncaName": "Central Bank of Ireland",
                  "ncaEmail": "dora@centralbank.ie",
                  "jurisdictionIso": "IE"
                }
                """;

        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalName").value("Nexus Bank Updated"))
                .andExpect(jsonPath("$.ncaName").value("Central Bank of Ireland"))
                .andExpect(jsonPath("$.jurisdictionIso").value("IE"));
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: OPS_ANALYST GET /api/v1/admin/tenant returns 403 (admin role required)")
    void opsAnalyst_getTenant_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: unauthenticated GET /api/v1/admin/tenant returns 401")
    void unauthenticated_getTenant_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenant"))
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
