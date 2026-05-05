package com.dora.smoke.admin;

import com.dora.dto.LoginRequest;
import com.dora.dto.LoginResponse;
import com.dora.repositories.AuditLogRepository;
import com.dora.services.audit.AuditAction;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-8 smoke tests: each admin mutation produces an audit_log row.
 *
 * <p>Uses @SpringBootTest + direct repository assertion on audit_log to confirm
 * that AuditService.record() is called within the same transaction as the mutation.
 */
@Tag("AC-8")
@DisplayName("AC-8: Admin mutations write to audit_log")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AdminAuditIntegrationSmokeTest {

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
    AuditLogRepository auditLogRepository;

    private String platformToken;

    @BeforeEach
    void obtainToken() throws Exception {
        platformToken = loginAndGetToken("platform@dora.local", "ChangeMe!23");
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: PUT /api/v1/admin/tenant writes TENANT_CONFIG_UPDATED audit row")
    void putTenant_writesAuditRow() throws Exception {
        long before = auditLogRepository.count();

        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "Nexus Bank Audit Test",
                                  "lei": "NEXUSBNK000000000001"
                                }
                                """))
                .andExpect(status().isOk());

        long after = auditLogRepository.count();
        assertThat(after).isGreaterThan(before);

        // Verify at least one TENANT_CONFIG_UPDATED row was created
        boolean hasAuditRow = auditLogRepository.findAll().stream()
                .anyMatch(row -> AuditAction.TENANT_CONFIG_UPDATED.name().equals(row.getAction()));
        assertThat(hasAuditRow).as("TENANT_CONFIG_UPDATED audit row must exist").isTrue();
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: POST /api/v1/admin/critical-services writes CRITICAL_SERVICE_CREATED audit row")
    void postCriticalService_writesAuditRow() throws Exception {
        long before = auditLogRepository.count();

        mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Audit Test Service", "description": "AC-8 verification" }
                                """))
                .andExpect(status().isCreated());

        long after = auditLogRepository.count();
        assertThat(after).isGreaterThan(before);

        boolean hasAuditRow = auditLogRepository.findAll().stream()
                .anyMatch(row -> AuditAction.CRITICAL_SERVICE_CREATED.name().equals(row.getAction()));
        assertThat(hasAuditRow).as("CRITICAL_SERVICE_CREATED audit row must exist").isTrue();
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: PUT /api/v1/admin/nca-email writes NCA_EMAIL_CONFIG_UPDATED audit row")
    void putNcaEmail_writesAuditRow() throws Exception {
        long before = auditLogRepository.count();

        mockMvc.perform(put("/api/v1/admin/nca-email")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sender": "audit-test@nexusbank.com",
                                  "recipient": "nca@regulator.eu",
                                  "subjectTemplate": "AC-8 test subject"
                                }
                                """))
                .andExpect(status().isOk());

        long after = auditLogRepository.count();
        assertThat(after).isGreaterThan(before);

        boolean hasAuditRow = auditLogRepository.findAll().stream()
                .anyMatch(row -> AuditAction.NCA_EMAIL_CONFIG_UPDATED.name().equals(row.getAction()));
        assertThat(hasAuditRow).as("NCA_EMAIL_CONFIG_UPDATED audit row must exist").isTrue();
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: POST /api/v1/admin/client-base writes CLIENT_BASE_ENTRY_ADDED audit row")
    void postClientBase_writesAuditRow() throws Exception {
        long before = auditLogRepository.count();

        mockMvc.perform(post("/api/v1/admin/client-base")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientCount": 99999,
                                  "effectiveFrom": "2026-03-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated());

        long after = auditLogRepository.count();
        assertThat(after).isGreaterThan(before);

        boolean hasAuditRow = auditLogRepository.findAll().stream()
                .anyMatch(row -> AuditAction.CLIENT_BASE_ENTRY_ADDED.name().equals(row.getAction()));
        assertThat(hasAuditRow).as("CLIENT_BASE_ENTRY_ADDED audit row must exist").isTrue();
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
