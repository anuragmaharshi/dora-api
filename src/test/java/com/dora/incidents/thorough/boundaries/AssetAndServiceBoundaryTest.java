package com.dora.incidents.thorough.boundaries;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-4 and AC-5 boundary tests for service linking and asset linking.
 *
 * AC-4 gaps beyond smoke tests:
 * - Empty serviceIds in linkServices request → 400 (@NotEmpty)
 * - Multiple service IDs in a single request (all valid) → 204
 * - Linking same service twice is idempotent (ManyToMany Set semantics)
 *
 * AC-5 gaps beyond smoke tests:
 * - Missing type field → 400
 * - Name exactly 200 chars → 201 (boundary)
 * - Name exactly 201 chars → 400 (over boundary)
 * - Type exactly 100 chars → 201 (boundary)
 * - Type exactly 101 chars → 400 (over boundary)
 * - Multiple assets on same incident appear in GET detail
 * - Asset on non-existent incident → 404
 */
@DisplayName("AC-4/AC-5 boundaries: service and asset link field constraints")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AssetAndServiceBoundaryTest {

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

    // ── AC-4: empty serviceIds in POST /services → 400 ───────────────────────

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4 boundary: empty serviceIds list in linkServices request is rejected (returns 400)")
    void linkServices_emptyServiceIds_returns400() throws Exception {
        String incidentId = createIncident(opsToken);
        String body = """
                { "serviceIds": [] }
                """;

        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/services")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── AC-4: multiple service IDs in single request (all valid) ─────────────

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4 boundary: linking multiple active services in one request returns 204")
    void linkServices_multipleActiveServiceIds_returns204() throws Exception {
        String serviceId1 = createCriticalService("SWIFT Network", platformToken);
        String serviceId2 = createCriticalService("Core Banking API", platformToken);

        String incidentId = createIncident(opsToken);

        String body = String.format("""
                { "serviceIds": ["%s", "%s"] }
                """, serviceId1, serviceId2);

        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/services")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        // Verify both services appear in GET detail
        MvcResult detail = mockMvc.perform(get("/api/v1/incidents/" + incidentId)
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(detail.getResponse().getContentAsString());
        JsonNode services = response.get("services");
        assertThat(services.size()).isGreaterThanOrEqualTo(2);
    }

    // ── AC-4: linking same service twice is idempotent ────────────────────────

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4 boundary: linking the same service twice is idempotent (second call returns 204, no duplicate)")
    void linkServices_sameServiceTwice_isIdempotent() throws Exception {
        String serviceId = createCriticalService("Idempotent Service Link", platformToken);
        String incidentId = createIncident(opsToken);

        String body = String.format("""
                { "serviceIds": ["%s"] }
                """, serviceId);

        // Link once
        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/services")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        // Link again (same service) — must not error or duplicate
        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/services")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        // Only one occurrence of this service in the response
        MvcResult detail = mockMvc.perform(get("/api/v1/incidents/" + incidentId)
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(detail.getResponse().getContentAsString());
        JsonNode services = response.get("services");
        long count = 0;
        for (JsonNode svc : services) {
            // LinkedServiceResponse uses field name "serviceId" not "id"
            JsonNode serviceIdNode = svc.get("serviceId");
            if (serviceIdNode != null && serviceId.equals(serviceIdNode.asText())) {
                count++;
            }
        }
        assertThat(count).isEqualTo(1);
    }

    // ── AC-5: missing type → 400 ──────────────────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5 boundary: missing type in linkAsset request is rejected (returns 400)")
    void linkAsset_missingType_returns400() throws Exception {
        String incidentId = createIncident(opsToken);
        String body = """
                {
                  "name": "Payment Router"
                }
                """;

        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/assets")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── AC-5: asset name exactly 200 chars is accepted ────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5 boundary: asset name exactly 200 chars is accepted (returns 201)")
    void linkAsset_name200Chars_returns201() throws Exception {
        String incidentId = createIncident(opsToken);
        String maxName = "N".repeat(200);
        String body = String.format("""
                {
                  "name": "%s",
                  "type": "SERVER"
                }
                """, maxName);

        MvcResult result = mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/assets")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.get("name").asText()).hasSize(200);
    }

    // ── AC-5: asset name 201 chars is rejected ────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5 boundary: asset name 201 chars is rejected (returns 400)")
    void linkAsset_name201Chars_returns400() throws Exception {
        String incidentId = createIncident(opsToken);
        String overName = "N".repeat(201);
        String body = String.format("""
                {
                  "name": "%s",
                  "type": "SERVER"
                }
                """, overName);

        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/assets")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── AC-5: asset type exactly 100 chars is accepted ────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5 boundary: asset type exactly 100 chars is accepted (returns 201)")
    void linkAsset_type100Chars_returns201() throws Exception {
        String incidentId = createIncident(opsToken);
        String maxType = "T".repeat(100);
        String body = String.format("""
                {
                  "name": "Asset With Long Type",
                  "type": "%s"
                }
                """, maxType);

        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/assets")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    // ── AC-5: asset type 101 chars is rejected ────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5 boundary: asset type 101 chars is rejected (returns 400)")
    void linkAsset_type101Chars_returns400() throws Exception {
        String incidentId = createIncident(opsToken);
        String overType = "T".repeat(101);
        String body = String.format("""
                {
                  "name": "Asset With Over-Length Type",
                  "type": "%s"
                }
                """, overType);

        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/assets")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── AC-5: multiple assets on same incident appear in GET detail ───────────

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5 boundary: multiple assets linked to same incident all appear in GET detail")
    void linkAsset_multipleAssets_allReturnedInDetail() throws Exception {
        String incidentId = createIncident(opsToken);

        // Link first asset
        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/assets")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Primary Router", "type": "NETWORK_DEVICE" }
                                """))
                .andExpect(status().isCreated());

        // Link second asset
        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/assets")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Backup Server", "type": "SERVER" }
                                """))
                .andExpect(status().isCreated());

        // GET detail must return both assets
        MvcResult detail = mockMvc.perform(get("/api/v1/incidents/" + incidentId)
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assets").isArray())
                .andReturn();

        JsonNode response = objectMapper.readTree(detail.getResponse().getContentAsString());
        JsonNode assets = response.get("assets");
        assertThat(assets.size()).isGreaterThanOrEqualTo(2);
    }

    // ── AC-5: asset on non-existent incident → 404 ───────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5 failure: linking asset to non-existent incident returns 404")
    void linkAsset_nonExistentIncident_returns404() throws Exception {
        String unknownId = UUID.randomUUID().toString();
        String body = """
                { "name": "Orphan Asset", "type": "SERVER" }
                """;

        mockMvc.perform(post("/api/v1/incidents/" + unknownId + "/assets")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String createCriticalService(String name, String token) throws Exception {
        String body = String.format("""
                { "name": "%s", "description": "Boundary test service" }
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
                  "title": "Service/Asset Boundary Test Incident",
                  "description": "Boundary testing",
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
