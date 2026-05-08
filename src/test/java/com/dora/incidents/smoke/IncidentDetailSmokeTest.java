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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-6: GET incident detail returns full incident including attachments, services, assets.
 *
 * <p>Verifies that after creating an incident with an inline asset, the GET endpoint:
 * 1. Returns HTTP 200 with the correct incidentId and status.
 * 2. Returns the assets array with the inline asset created during POST.
 * 3. Returns 404 for an unknown incident UUID.
 * 4. Returns 200 for INCIDENT_MANAGER role (not just OPS_ANALYST).
 */
@Tag("AC-6")
@DisplayName("AC-6: GET incident detail — full response including linked entities")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class IncidentDetailSmokeTest {

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
    private String managerToken;

    @BeforeEach
    void obtainTokens() throws Exception {
        opsToken = loginAndGetToken("ops@dora.local", "ChangeMe!23");
        managerToken = loginAndGetToken("incident@dora.local", "ChangeMe!23");
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: GET /incidents/{id} returns 200 with full detail including assets")
    void getIncident_returns200WithFullDetail() throws Exception {
        // Create incident with an inline asset
        String createBody = """
                {
                  "title": "Detail Test Incident",
                  "description": "Full detail retrieval test",
                  "serviceIds": [],
                  "assets": [
                    { "name": "Core Router", "type": "NETWORK_DEVICE" }
                  ]
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String incidentUuid = created.get("id").asText();
        String incidentId = created.get("incidentId").asText();

        // GET the incident
        MvcResult getResult = mockMvc.perform(get("/api/v1/incidents/" + incidentUuid)
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incidentUuid))
                .andExpect(jsonPath("$.incidentId").value(incidentId))
                .andExpect(jsonPath("$.status").value("DETECTED"))
                .andExpect(jsonPath("$.assets").isArray())
                .andReturn();

        JsonNode response = objectMapper.readTree(getResult.getResponse().getContentAsString());
        JsonNode assets = response.get("assets");
        assertThat(assets.size()).isGreaterThan(0);
        assertThat(assets.get(0).get("name").asText()).isEqualTo("Core Router");
        assertThat(assets.get(0).get("type").asText()).isEqualTo("NETWORK_DEVICE");
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: GET /incidents/{id} returns 200 for INCIDENT_MANAGER role")
    void getIncident_incidentManagerRole_returns200() throws Exception {
        String incidentId = createIncident(managerToken);

        mockMvc.perform(get("/api/v1/incidents/" + incidentId)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: GET /incidents/{id} returns 404 for unknown UUID")
    void getIncident_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/incidents/00000000-0000-0000-0000-000000000099")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: GET /incidents returns paginated list")
    void listIncidents_returns200WithPage() throws Exception {
        createIncident(opsToken);
        createIncident(opsToken);

        mockMvc.perform(get("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String createIncident(String token) throws Exception {
        String body = """
                {
                  "title": "Detail Test Incident",
                  "description": "AC-6 smoke test",
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
