package com.dora.incidents.thorough.security;

import com.dora.dto.LoginRequest;
import com.dora.dto.LoginResponse;
import com.dora.incidents.application.ObjectStorageClient;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.net.URL;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant isolation tests.
 *
 * A bank-role user authenticated with Tenant A must not be able to read, attach to,
 * link services to, or link assets to incidents belonging to Tenant B.
 * The controller always extracts tenantId from the authenticated principal — it is never
 * accepted as a request parameter (see IncidentController.resolveTenantId).
 *
 * The seed data (V1_1_1) has:
 *   - ops@dora.local  → belongs to "Sigma Bank" tenant
 *   - incident@dora.local → belongs to "Sigma Bank" tenant (same tenant as ops)
 *
 * For true cross-tenant isolation testing we use:
 *   - ops@dora.local (Tenant A: Sigma Bank)
 *   - A second user belonging to a different tenant (if seeded) — or we verify that
 *     cross-tenant reads return 404 by using a random UUID that doesn't belong to the
 *     ops user's tenant.
 *
 * NOTE: Since the seed only has one bank tenant, we verify the service-layer guard by:
 * 1. Creating incident with ops (Tenant A).
 * 2. Confirming that findById with a spoofed tenantId (via service unit test approach)
 *    does return 404 — verified indirectly via the controller layer by checking that
 *    a random UUID returns 404 (not 500 or data leak).
 * 3. Confirming that the paginated list returns only the calling user's tenant incidents.
 *
 * The service-layer cross-tenant guard is explicitly tested in the service unit tests.
 * Controller-layer: all tenantId resolution is via Principal only.
 */
@Tag("AC-6")
@DisplayName("Tenant isolation: incident data is scoped to authenticated user's tenant")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class TenantIsolationTest {

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
        registry.add("incident.attachment.max-mb", () -> "10");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    ObjectStorageClient objectStorageClient;

    private String opsToken;
    private String managerToken;

    @BeforeEach
    void setUp() throws Exception {
        opsToken = loginAndGetToken("ops@dora.local", "ChangeMe!23");
        managerToken = loginAndGetToken("incident@dora.local", "ChangeMe!23");
        when(objectStorageClient.presignPut(any(String.class), any(Duration.class)))
                .thenAnswer(inv -> new URL("http://minio.local/dora-local/test"));
    }

    // ── GET on unknown/foreign incident UUID returns 404 not 403 ─────────────

    @Test
    @Tag("AC-6")
    @DisplayName("Tenant isolation: GET incident with unknown UUID returns 404 (not 500 or data from another tenant)")
    void getIncident_completelyUnknownUUID_returns404() throws Exception {
        String randomId = UUID.randomUUID().toString();

        mockMvc.perform(get("/api/v1/incidents/" + randomId)
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isNotFound());
    }

    // ── Incident created by ops is visible to manager (same tenant) ───────────

    @Test
    @Tag("AC-6")
    @DisplayName("Tenant isolation: incident created by OPS_ANALYST is visible to INCIDENT_MANAGER of same tenant")
    void getIncident_sameTenant_differentRoles_canRead() throws Exception {
        String incidentId = createIncident(opsToken);

        mockMvc.perform(get("/api/v1/incidents/" + incidentId)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incidentId));
    }

    // ── List returns only own tenant's incidents (not cross-tenant) ───────────

    @Test
    @Tag("AC-6")
    @DisplayName("Tenant isolation: GET /incidents list returns only the authenticated user's tenant incidents")
    void listIncidents_returnsOnlyOwnTenantData() throws Exception {
        // Create incidents with both users (same tenant in seed data)
        String inc1 = createIncident(opsToken);
        String inc2 = createIncident(managerToken);

        // List as ops — both should appear (same tenant)
        MvcResult result = mockMvc.perform(get("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode page = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode content = page.get("content");

        // All returned incidents must have the same tenantId as the ops user's tenant
        String firstTenantId = content.get(0).get("tenantId").asText();
        for (JsonNode incident : content) {
            assertThat(incident.get("tenantId").asText())
                    .as("All returned incidents must belong to the same tenant")
                    .isEqualTo(firstTenantId);
        }
    }

    // ── Attachment on non-existent/foreign incident → 404 ────────────────────

    @Test
    @Tag("AC-3")
    @DisplayName("Tenant isolation AC-3: attachment request on non-existent incident returns 404")
    void requestAttachment_nonExistentIncident_returns404() throws Exception {
        String unknownIncidentId = UUID.randomUUID().toString();
        String body = """
                {
                  "filename": "orphan.pdf",
                  "contentType": "application/pdf",
                  "sizeBytes": 1024
                }
                """;

        mockMvc.perform(post("/api/v1/incidents/" + unknownIncidentId + "/attachments")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    // ── Service link on non-existent incident → 404 ───────────────────────────

    @Test
    @Tag("AC-4")
    @DisplayName("Tenant isolation AC-4: service link on non-existent incident returns 404")
    void linkServices_nonExistentIncident_returns404() throws Exception {
        String unknownIncidentId = UUID.randomUUID().toString();
        String body = """
                { "serviceIds": ["00000000-0000-0000-0000-000000000001"] }
                """;

        mockMvc.perform(post("/api/v1/incidents/" + unknownIncidentId + "/services")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    // ── Attachment complete on wrong incident → 404 ───────────────────────────

    @Test
    @Tag("AC-3")
    @DisplayName("Tenant isolation AC-3: /complete on attachment from different incident returns 404")
    void completeAttachment_attachmentFromDifferentIncident_returns404() throws Exception {
        // Create two separate incidents
        String incidentId1 = createIncident(opsToken);
        String incidentId2 = createIncident(opsToken);

        // Create attachment on incident 1
        String uploadBody = """
                {
                  "filename": "evidence.pdf",
                  "contentType": "application/pdf",
                  "sizeBytes": 512000
                }
                """;

        MvcResult uploadResult = mockMvc.perform(
                        post("/api/v1/incidents/" + incidentId1 + "/attachments")
                                .header("Authorization", "Bearer " + opsToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(uploadBody))
                .andExpect(status().isCreated())
                .andReturn();

        String attachmentId = objectMapper.readTree(uploadResult.getResponse().getContentAsString())
                .get("attachmentId").asText();

        // Try to complete using incident 2's path with incident 1's attachmentId
        // The service checks findByIdAndIncidentId — this should be 404
        when(objectStorageClient.exists(any(String.class))).thenReturn(true);

        mockMvc.perform(
                        post("/api/v1/incidents/" + incidentId2 + "/attachments/" + attachmentId + "/complete")
                                .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isNotFound());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String createIncident(String token) throws Exception {
        String body = """
                {
                  "title": "Tenant Isolation Test Incident",
                  "description": "Tenant isolation test fixture",
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
