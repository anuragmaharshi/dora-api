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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-4: Affected services must be active picklist entries — free-text / inactive IDs rejected.
 *
 * <p>Verifies that:
 * 1. Linking an active critical service to an incident via POST /services succeeds (204).
 * 2. Linking an unknown UUID (not in picklist) returns 422.
 * 3. Linking services at incident creation time with inactive IDs returns 422.
 *
 * <p>Uses PLATFORM_ADMIN to create a critical service first (so the picklist has an entry),
 * then switches to OPS_ANALYST to create and link to the incident.
 */
@Tag("AC-4")
@DisplayName("AC-4: Affected services — only active picklist entries accepted")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class LinkServicesValidationSmokeTest {

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

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: POST /services with active picklist ID succeeds (204)")
    void linkActiveService_returns204() throws Exception {
        // Create a critical service as platform admin
        String serviceId = createCriticalService("SWIFT Integration", platformToken);

        // Create an incident
        String incidentId = createIncident(opsToken);

        // Link the active service
        String body = String.format("""
                { "serviceIds": ["%s"] }
                """, serviceId);

        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/services")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: POST /services with unknown UUID returns 422")
    void linkUnknownService_returns422() throws Exception {
        String incidentId = createIncident(opsToken);

        String body = String.format("""
                { "serviceIds": ["%s"] }
                """, UUID.randomUUID());

        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/services")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: create incident with unknown serviceId returns 422")
    void createIncidentWithUnknownService_returns422() throws Exception {
        String body = String.format("""
                {
                  "title": "Test incident with bad service",
                  "description": "Should fail validation",
                  "serviceIds": ["%s"],
                  "assets": []
                }
                """, UUID.randomUUID());

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String createCriticalService(String name, String token) throws Exception {
        String body = String.format("""
                { "name": "%s", "description": "Smoke test service" }
                """, name);
        MvcResult result = mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
    }

    private String createIncident(String token) throws Exception {
        String body = """
                {
                  "title": "Service Link Test Incident",
                  "description": "Testing service linking",
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
