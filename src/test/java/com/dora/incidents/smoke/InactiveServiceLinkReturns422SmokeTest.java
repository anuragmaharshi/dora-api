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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bug #23 regression test: linking an archived (inactive) critical service to an incident
 * must return 422 Unprocessable Entity, not 201 Created.
 *
 * <p>Root cause: The E2E client sends {@code affectedServiceIds} but the DTO field was
 * {@code serviceIds}. Jackson ignored the unknown field, silently treated the service list
 * as null, bypassed validation, and returned 201. Fix: added {@code @JsonAlias("affectedServiceIds")}
 * to {@code CreateIncidentRequest.serviceIds}.
 *
 * <p>This test covers both the field-alias path (sends {@code affectedServiceIds}) and the
 * canonical path (sends {@code serviceIds}) to guard against regression in either form.
 */
@Tag("bug-23")
@DisplayName("Bug #23: linking archived service to incident must return 422")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class InactiveServiceLinkReturns422SmokeTest {

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
     * Bug #23, path A: E2E sends {@code affectedServiceIds} (alias).
     * After archiving the service, creating an incident with the archived ID via the alias
     * field must return 422 — not 201.
     */
    @Test
    @Tag("bug-23")
    @DisplayName("Bug #23a: affectedServiceIds alias with archived service ID returns 422")
    void createIncident_withArchivedServiceId_viaAlias_returns422() throws Exception {
        // Create and immediately archive a critical service
        String archivedServiceId = createAndArchiveService("Archived-Alias-" + System.nanoTime());

        // Send using the alias field name (as the E2E client does)
        String body = String.format("""
                {
                  "title": "Test with archived service via alias",
                  "description": "Should be rejected",
                  "affectedServiceIds": ["%s"]
                }
                """, archivedServiceId);

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    /**
     * Bug #23, path B: canonical field name {@code serviceIds} with archived service ID.
     * The validation logic in {@code resolveActiveServices} must reject archived IDs regardless
     * of which JSON field name was used for deserialization.
     */
    @Test
    @Tag("bug-23")
    @DisplayName("Bug #23b: serviceIds canonical field with archived service ID returns 422")
    void createIncident_withArchivedServiceId_viaCanonicalName_returns422() throws Exception {
        String archivedServiceId = createAndArchiveService("Archived-Canon-" + System.nanoTime());

        String body = String.format("""
                {
                  "title": "Test with archived service canonical",
                  "description": "Should also be rejected",
                  "serviceIds": ["%s"]
                }
                """, archivedServiceId);

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    /**
     * Positive case: creating with an active service via the alias field must still return 201.
     * Guards against the alias fix accidentally breaking the happy path.
     */
    @Test
    @Tag("bug-23")
    @DisplayName("Bug #23c: affectedServiceIds alias with active service ID still returns 201")
    void createIncident_withActiveServiceId_viaAlias_returns201() throws Exception {
        String activeServiceId = createActiveService("Active-Alias-" + System.nanoTime());

        String body = String.format("""
                {
                  "title": "Test with active service via alias",
                  "description": "Should succeed",
                  "affectedServiceIds": ["%s"]
                }
                """, activeServiceId);

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String createAndArchiveService(String name) throws Exception {
        String serviceId = createActiveService(name);

        mockMvc.perform(post("/api/v1/admin/critical-services/" + serviceId + "/archive")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isNoContent());

        return serviceId;
    }

    private String createActiveService(String name) throws Exception {
        String body = String.format("""
                { "name": "%s", "description": "Smoke test service" }
                """, name);
        MvcResult result = mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
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
