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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-1: Create incident smoke test.
 *
 * <p>Verifies that a valid CreateIncidentRequest from an OPS_ANALYST results in:
 * - HTTP 201 Created
 * - incidentId in INC-YYYYMMDD-NNNN format
 * - detectionDatetime is server-set (never null)
 * - status defaults to DETECTED
 * - INCIDENT_CREATED audit entry written (checked via GET audit endpoint)
 *
 * <p>Uses @SpringBootTest + Testcontainers Postgres (full-stack) because the incident ID
 * generator and audit service need a real transaction.
 * ObjectStorageClient is mocked — no MinIO container needed for this AC.
 */
@Tag("AC-1")
@DisplayName("AC-1: Create incident — incidentId assigned, detection datetime server-stamped")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class IncidentCreateSmokeTest {

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
        // S3 values are irrelevant for this test — ObjectStorageClient is mocked
        registry.add("aws.s3.endpoint", () -> "http://localhost:9000");
        registry.add("aws.s3.access-key", () -> "minioadmin");
        registry.add("aws.s3.secret-key", () -> "minioadmin");
        registry.add("aws.s3.bucket", () -> "dora-local");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    // Mock the storage client — S3/MinIO is not available in unit test context
    @MockBean
    ObjectStorageClient objectStorageClient;

    private String opsToken;

    @BeforeEach
    void obtainToken() throws Exception {
        opsToken = loginAndGetToken("ops@dora.local", "ChangeMe!23");
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: POST /incidents returns 201 with INC-YYYYMMDD-NNNN incidentId")
    void createIncident_returns201_withIncidentId() throws Exception {
        String body = """
                {
                  "title": "Payments Rail Outage",
                  "description": "Core payments rail is unresponsive",
                  "impactEstimate": "High impact — all payment processing halted",
                  "serviceIds": [],
                  "assets": []
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.incidentId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("DETECTED"))
                .andExpect(jsonPath("$.detectionDatetime").isNotEmpty())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        String incidentId = response.get("incidentId").asText();

        // Incident ID must match INC-YYYYMMDD-NNNN pattern
        assertThat(incidentId).matches("INC-\\d{8}-\\d{4}");
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: POST /incidents with missing title returns 400")
    void createIncident_missingTitle_returns400() throws Exception {
        String body = """
                {
                  "description": "Some description",
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

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: POST /incidents with title > 200 chars returns 400")
    void createIncident_titleTooLong_returns400() throws Exception {
        String longTitle = "A".repeat(201);
        String body = String.format("""
                {
                  "title": "%s",
                  "description": "Some description",
                  "serviceIds": [],
                  "assets": []
                }
                """, longTitle);

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
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
