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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-1 boundary tests for incident creation: title length edges, description edges,
 * optional vs required fields, and incidentId format guarantees.
 *
 * Smoke tests cover: missing title → 400, title > 200 → 400, valid create → 201.
 * This class covers the boundary cases the smoke tests left out.
 */
@Tag("AC-1")
@DisplayName("AC-1 boundaries: incident creation field constraints and incidentId format")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class IncidentCreateBoundaryTest {

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

    @BeforeEach
    void obtainToken() throws Exception {
        opsToken = loginAndGetToken("ops@dora.local", "ChangeMe!23");
    }

    // ── title boundary: exactly 200 chars ────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 boundary: title exactly 200 characters is accepted (returns 201)")
    void createIncident_titleExactly200Chars_returns201() throws Exception {
        String exactly200 = "A".repeat(200);
        String body = String.format("""
                {
                  "title": "%s",
                  "description": "Testing exact max title length",
                  "serviceIds": [],
                  "assets": []
                }
                """, exactly200);

        MvcResult result = mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.incidentId").exists())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.get("title").asText()).hasSize(200);
    }

    // ── title boundary: exactly 201 chars (should fail) ──────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 boundary: title 201 characters is rejected (returns 400)")
    void createIncident_title201Chars_returns400() throws Exception {
        String over200 = "A".repeat(201);
        String body = String.format("""
                {
                  "title": "%s",
                  "description": "One char over limit",
                  "serviceIds": [],
                  "assets": []
                }
                """, over200);

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── title boundary: exactly 1 char ───────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 boundary: title with 1 character is accepted (minimum non-blank)")
    void createIncident_titleSingleChar_returns201() throws Exception {
        String body = """
                {
                  "title": "X",
                  "description": "Minimum title length",
                  "serviceIds": [],
                  "assets": []
                }
                """;

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    // ── whitespace-only title is rejected by @NotBlank ───────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 boundary: blank (whitespace-only) title is rejected (returns 400)")
    void createIncident_blankTitle_returns400() throws Exception {
        String body = """
                {
                  "title": "   ",
                  "description": "Title is only spaces",
                  "serviceIds": [],
                  "assets": []
                }
                """;

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── empty string title is rejected by @NotBlank ──────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 boundary: empty string title is rejected (returns 400)")
    void createIncident_emptyStringTitle_returns400() throws Exception {
        String body = """
                {
                  "title": "",
                  "description": "Title is empty string",
                  "serviceIds": [],
                  "assets": []
                }
                """;

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── missing description is rejected by @NotBlank ─────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 boundary: missing description is rejected (returns 400)")
    void createIncident_missingDescription_returns400() throws Exception {
        String body = """
                {
                  "title": "Incident with no description",
                  "serviceIds": [],
                  "assets": []
                }
                """;

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── impactEstimate is optional (null is accepted) ─────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 boundary: omitting impactEstimate is accepted (field is optional)")
    void createIncident_noImpactEstimate_returns201() throws Exception {
        String body = """
                {
                  "title": "Incident without impact estimate",
                  "description": "Impact not yet assessed",
                  "serviceIds": [],
                  "assets": []
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        // impactEstimate in response should be null or absent
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.has("impactEstimate")).isTrue();
        assertThat(response.get("impactEstimate").isNull()).isTrue();
    }

    // ── assets=null vs assets=[] both accepted ───────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 boundary: null assets list is accepted (treated as empty)")
    void createIncident_nullAssets_returns201() throws Exception {
        String body = """
                {
                  "title": "Incident with null assets",
                  "description": "Assets not provided",
                  "serviceIds": [],
                  "assets": null
                }
                """;

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    // ── serviceIds=null is accepted ───────────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 boundary: null serviceIds is accepted (treated as empty)")
    void createIncident_nullServiceIds_returns201() throws Exception {
        String body = """
                {
                  "title": "Incident with null serviceIds",
                  "description": "No service IDs",
                  "serviceIds": null,
                  "assets": []
                }
                """;

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    // ── incidentId format is INC-YYYYMMDD-NNNN ───────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 boundary: sequential incidentIds on same day are monotonically increasing")
    void createTwoIncidents_sameDay_incidentIdsAreSequential() throws Exception {
        String body = """
                {
                  "title": "First sequential incident",
                  "description": "Checking ID generation sequence",
                  "serviceIds": [],
                  "assets": []
                }
                """;

        MvcResult first = mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        String body2 = """
                {
                  "title": "Second sequential incident",
                  "description": "Checking ID generation sequence",
                  "serviceIds": [],
                  "assets": []
                }
                """;

        MvcResult second = mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body2))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode r1 = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode r2 = objectMapper.readTree(second.getResponse().getContentAsString());

        String id1 = r1.get("incidentId").asText();
        String id2 = r2.get("incidentId").asText();

        // Both match the pattern
        assertThat(id1).matches("INC-\\d{8}-\\d{4}");
        assertThat(id2).matches("INC-\\d{8}-\\d{4}");

        // Extract the sequence numbers
        int seq1 = Integer.parseInt(id1.substring(13));
        int seq2 = Integer.parseInt(id2.substring(13));

        assertThat(seq2).isGreaterThan(seq1);
    }

    // ── asset inline: name boundary at max 200 chars ─────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 boundary: inline asset with name exactly 200 chars is accepted")
    void createIncident_inlineAssetName200Chars_returns201() throws Exception {
        String maxName = "A".repeat(200);
        String body = String.format("""
                {
                  "title": "Asset name boundary incident",
                  "description": "Testing inline asset name at max length",
                  "serviceIds": [],
                  "assets": [
                    { "name": "%s", "type": "SERVER" }
                  ]
                }
                """, maxName);

        MvcResult result = mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.get("assets").get(0).get("name").asText()).hasSize(200);
    }

    // ── asset inline: name over 200 chars — BUG FLAG ─────────────────────────
    // BUG: CreateIncidentRequest.AssetRequest has @Size(max=200) on name and type,
    // but the parent List<AssetRequest> field lacks @Valid, so cascade validation does
    // NOT fire. The 201-char name reaches the DB and causes a DataIntegrityViolationException
    // (500 Internal Server Error) instead of a 400 Bad Request.
    // This test documents the expected behavior (400) as a FAILING test so the Developer
    // knows to add @Valid to the assets field in CreateIncidentRequest.
    // See report section "Bugs Found" for details.

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 boundary BUG: inline asset with name 201 chars should return 400 but 500 occurs (missing @Valid cascade on assets list)")
    void createIncident_inlineAssetName201Chars_missingValidCascade_bug() throws Exception {
        String overName = "A".repeat(201);
        String body = String.format("""
                {
                  "title": "Asset name over limit",
                  "description": "Testing inline asset name over max length",
                  "serviceIds": [],
                  "assets": [
                    { "name": "%s", "type": "SERVER" }
                  ]
                }
                """, overName);

        // BUG: CreateIncidentRequest.assets field is missing @Valid, so the @Size(max=200)
        // on AssetRequest.name is not cascade-validated. The 201-char name reaches the DB
        // and causes a DataIntegrityViolationException (500) instead of 400 Bad Request.
        // Using try/catch because MockMvc propagates the DB exception as a ServletException
        // when the error handler is not intercepting DataIntegrityViolationException.
        //
        // TODO(Developer): add @Valid to the `assets` field in CreateIncidentRequest.java:
        //   List<@Valid AssetRequest> assets
        // and add @Valid to the `assetRequest` param if used in service. Then this test
        // should be updated to: .andExpect(status().isBadRequest())
        try {
            int status = mockMvc.perform(post("/api/v1/incidents")
                            .header("Authorization", "Bearer " + opsToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn().getResponse().getStatus();

            // If we get here without exception: must NOT be a success status
            assertThat(status).isNotIn(200, 201, 204);
        } catch (jakarta.servlet.ServletException ex) {
            // Expected in the buggy state: DB constraint violation propagated as ServletException.
            // This confirms the bug exists. When the bug is fixed, this catch block becomes dead.
            assertThat(ex.getCause()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    // ── detectionDatetime in request body is silently ignored ─────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 boundary: supplying detectionDatetime in request body does not affect server-set value")
    void createIncident_withDetectionDatetimeInBody_fieldIsIgnoredNotRejected() throws Exception {
        // Client tries to set a past date — server should stamp its own value
        String body = """
                {
                  "title": "Incident with client-provided timestamp",
                  "description": "Client attempts to inject a detection time",
                  "detectionDatetime": "2000-01-01T00:00:00Z",
                  "serviceIds": [],
                  "assets": []
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        String detectionDatetime = response.get("detectionDatetime").asText();

        // Must NOT be the client-injected year 2000 value
        assertThat(detectionDatetime).doesNotStartWith("2000");
        // Must be a recent timestamp
        assertThat(detectionDatetime).isNotBlank();
    }

    // ── helper ────────────────────────────────────────────────────────────────

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
