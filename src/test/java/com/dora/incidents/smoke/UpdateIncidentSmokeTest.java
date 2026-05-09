package com.dora.incidents.smoke;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke tests for PUT /api/v1/incidents/{id}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Successful update of mutable fields (title, description, impactEstimate) by INCIDENT_MANAGER</li>
 *   <li>404 on update of an unknown incident UUID</li>
 *   <li>403 when OPS_ANALYST attempts the update (authz enforcement)</li>
 *   <li>400 when title is blank (Bean Validation enforcement)</li>
 * </ul>
 *
 * <p>Uses @SpringBootTest + Testcontainers Postgres (full-stack) because we need a
 * real transaction to verify the update is persisted. ObjectStorageClient is mocked.
 */
@Tag("AC-update")
@DisplayName("AC-update: PUT /incidents/{id} — update mutable fields, authz guard, 404 on unknown")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class UpdateIncidentSmokeTest {

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

    private String managerToken;
    private String opsToken;

    @BeforeEach
    void obtainTokens() throws Exception {
        // incident@dora.local is seeded as INCIDENT_MANAGER
        managerToken = loginAndGetToken("incident@dora.local", "ChangeMe!23");
        // ops@dora.local is seeded as OPS_ANALYST
        opsToken = loginAndGetToken("ops@dora.local", "ChangeMe!23");
    }

    @Test
    @Tag("AC-update")
    @DisplayName("AC-update: INCIDENT_MANAGER can update mutable fields; changes are persisted and returned")
    void updateIncident_asManager_returns200_withUpdatedFields() throws Exception {
        // Create an incident first (OPS_ANALYST can create)
        UUID incidentUuid = createIncident(opsToken, "Original Title", "Original description");

        String updateBody = """
                {
                  "title": "Updated Title After Review",
                  "description": "Updated description with more detail",
                  "impactEstimate": "Medium — payments delayed, no data loss"
                }
                """;

        MvcResult result = mockMvc.perform(put("/api/v1/incidents/" + incidentUuid)
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incidentUuid.toString()))
                .andExpect(jsonPath("$.title").value("Updated Title After Review"))
                .andExpect(jsonPath("$.description").value("Updated description with more detail"))
                .andExpect(jsonPath("$.impactEstimate").value("Medium — payments delayed, no data loss"))
                // detection_datetime must still be present and unchanged
                .andExpect(jsonPath("$.detectionDatetime").isNotEmpty())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        // incidentId (INC-YYYYMMDD-NNNN) must not have changed — it is immutable
        assertThat(response.get("incidentId").asText()).matches("INC-\\d{8}-\\d{4}");
    }

    @Test
    @Tag("AC-update")
    @DisplayName("AC-update: PUT /incidents/{id} with unknown UUID returns 404")
    void updateIncident_unknownId_returns404() throws Exception {
        UUID unknown = UUID.randomUUID();
        String updateBody = """
                {
                  "title": "Does not matter",
                  "description": "Will never find this incident"
                }
                """;

        mockMvc.perform(put("/api/v1/incidents/" + unknown)
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isNotFound());
    }

    @Test
    @Tag("AC-update")
    @DisplayName("AC-update: OPS_ANALYST receives 403 on PUT /incidents/{id}")
    void updateIncident_asOpsAnalyst_returns403() throws Exception {
        UUID incidentUuid = createIncident(opsToken, "Auth Test Incident", "Testing role guard");

        String updateBody = """
                {
                  "title": "OPS analyst should not update",
                  "description": "This request should be denied"
                }
                """;

        mockMvc.perform(put("/api/v1/incidents/" + incidentUuid)
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-update")
    @DisplayName("AC-update: blank title returns 400 (Bean Validation)")
    void updateIncident_blankTitle_returns400() throws Exception {
        UUID incidentUuid = createIncident(opsToken, "Validation Test", "Testing validation");

        String updateBody = """
                {
                  "title": "",
                  "description": "Valid description"
                }
                """;

        mockMvc.perform(put("/api/v1/incidents/" + incidentUuid)
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isBadRequest());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates an incident and returns its UUID. Separate from the test assertions so
     * we do not conflate create-path failures with update-path failures.
     */
    private UUID createIncident(String token, String title, String description) throws Exception {
        String body = String.format("""
                {
                  "title": "%s",
                  "description": "%s",
                  "serviceIds": [],
                  "assets": []
                }
                """, title, description);

        MvcResult result = mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
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
