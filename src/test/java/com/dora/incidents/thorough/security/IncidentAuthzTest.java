package com.dora.incidents.thorough.security;

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
 * Authorization thorough tests for incident endpoints.
 *
 * AC-6 gaps: COMPLIANCE_OFFICER and CISO can GET; BOARD_VIEWER is blocked (403).
 * AC-8 gaps: PLATFORM_ADMIN blocked on attachment, services, and assets endpoints
 *            (smoke only covers POST /incidents, GET /incidents, GET /incidents/{id}).
 * AC-1 gaps: COMPLIANCE_OFFICER and CISO can create incidents (they are in the @PreAuthorize list).
 * Cross-cutting: unauthenticated requests return 401, not 403.
 * Cross-cutting: BOARD_VIEWER (excluded by BLOCKER-1) returns 403 on all incident endpoints.
 */
@DisplayName("Incident authorization: role matrix enforcement across all endpoints")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class IncidentAuthzTest {

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
    private String complianceToken;
    private String cisoToken;
    private String boardToken;
    private String platformToken;

    @BeforeEach
    void obtainTokens() throws Exception {
        opsToken = loginAndGetToken("ops@dora.local", "ChangeMe!23");
        complianceToken = loginAndGetToken("compliance@dora.local", "ChangeMe!23");
        cisoToken = loginAndGetToken("ciso@dora.local", "ChangeMe!23");
        boardToken = loginAndGetToken("board@dora.local", "ChangeMe!23");
        platformToken = loginAndGetToken("platform@dora.local", "ChangeMe!23");
    }

    // ── AC-1: COMPLIANCE_OFFICER can create incidents ─────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 authz: COMPLIANCE_OFFICER can POST /incidents (returns 201)")
    void createIncident_complianceOfficerRole_returns201() throws Exception {
        String body = """
                {
                  "title": "Compliance Officer Incident",
                  "description": "Created by compliance officer",
                  "serviceIds": [],
                  "assets": []
                }
                """;

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + complianceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    // ── AC-1: CISO can create incidents ──────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 authz: CISO can POST /incidents (returns 201)")
    void createIncident_cisoRole_returns201() throws Exception {
        String body = """
                {
                  "title": "CISO Incident",
                  "description": "Created by CISO",
                  "serviceIds": [],
                  "assets": []
                }
                """;

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + cisoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    // ── AC-6: COMPLIANCE_OFFICER can GET incident detail ──────────────────────

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6 authz: COMPLIANCE_OFFICER can GET /incidents/{id} (returns 200)")
    void getIncident_complianceOfficerRole_returns200() throws Exception {
        // Create with ops, read with compliance
        String incidentId = createIncident(opsToken);

        mockMvc.perform(get("/api/v1/incidents/" + incidentId)
                        .header("Authorization", "Bearer " + complianceToken))
                .andExpect(status().isOk());
    }

    // ── AC-6: CISO can GET incident detail ────────────────────────────────────

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6 authz: CISO can GET /incidents/{id} (returns 200)")
    void getIncident_cisoRole_returns200() throws Exception {
        String incidentId = createIncident(cisoToken);

        mockMvc.perform(get("/api/v1/incidents/" + incidentId)
                        .header("Authorization", "Bearer " + cisoToken))
                .andExpect(status().isOk());
    }

    // ── AC-6: BOARD_VIEWER is blocked on GET incident detail (BLOCKER-1) ──────

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6 authz: BOARD_VIEWER is blocked from GET /incidents/{id} (returns 403, BLOCKER-1)")
    void getIncident_boardViewerRole_returns403() throws Exception {
        // Create with ops so we have a real incident
        String incidentId = createIncident(opsToken);

        mockMvc.perform(get("/api/v1/incidents/" + incidentId)
                        .header("Authorization", "Bearer " + boardToken))
                .andExpect(status().isForbidden());
    }

    // ── AC-6: BOARD_VIEWER is blocked on GET /incidents list ─────────────────

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6 authz: BOARD_VIEWER is blocked from GET /incidents list (returns 403)")
    void listIncidents_boardViewerRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/incidents")
                        .header("Authorization", "Bearer " + boardToken))
                .andExpect(status().isForbidden());
    }

    // ── AC-8: PLATFORM_ADMIN blocked on POST /incidents/{id}/attachments ──────

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8 authz: PLATFORM_ADMIN is blocked from POST /incidents/{id}/attachments (returns 403)")
    void requestAttachment_platformAdminRole_returns403() throws Exception {
        // Use ops to create the incident first
        String incidentId = createIncident(opsToken);

        String body = """
                {
                  "filename": "evidence.pdf",
                  "contentType": "application/pdf",
                  "sizeBytes": 1024
                }
                """;

        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/attachments")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ── AC-8: PLATFORM_ADMIN blocked on POST /incidents/{id}/services ─────────

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8 authz: PLATFORM_ADMIN is blocked from POST /incidents/{id}/services (returns 403)")
    void linkServices_platformAdminRole_returns403() throws Exception {
        String incidentId = createIncident(opsToken);

        String body = """
                { "serviceIds": ["00000000-0000-0000-0000-000000000001"] }
                """;

        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ── AC-8: PLATFORM_ADMIN blocked on POST /incidents/{id}/assets ───────────

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8 authz: PLATFORM_ADMIN is blocked from POST /incidents/{id}/assets (returns 403)")
    void linkAsset_platformAdminRole_returns403() throws Exception {
        String incidentId = createIncident(opsToken);

        String body = """
                { "name": "Router", "type": "NETWORK_DEVICE" }
                """;

        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/assets")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ── AC-8: PLATFORM_ADMIN blocked on POST /incidents/{id}/attachments/{id}/complete

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8 authz: PLATFORM_ADMIN is blocked from POST /incidents/{id}/attachments/{id}/complete (returns 403)")
    void completeAttachment_platformAdminRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/incidents/00000000-0000-0000-0000-000000000001" +
                        "/attachments/00000000-0000-0000-0000-000000000002/complete")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isForbidden());
    }

    // ── AC-8: unauthenticated on incident sub-resources → 401 ────────────────

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8 authz: unauthenticated POST /incidents/{id}/attachments returns 401")
    void requestAttachment_unauthenticated_returns401() throws Exception {
        String body = """
                {
                  "filename": "evidence.pdf",
                  "contentType": "application/pdf",
                  "sizeBytes": 1024
                }
                """;

        mockMvc.perform(post("/api/v1/incidents/00000000-0000-0000-0000-000000000001/attachments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String createIncident(String token) throws Exception {
        String body = """
                {
                  "title": "Authz Test Incident",
                  "description": "Authorization test fixture",
                  "serviceIds": [],
                  "assets": []
                }
                """;
        MvcResult result = mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
    }

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
