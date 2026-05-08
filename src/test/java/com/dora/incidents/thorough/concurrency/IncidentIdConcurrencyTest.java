package com.dora.incidents.thorough.concurrency;

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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Concurrency test for incident ID generation.
 *
 * The IncidentIdGenerator uses a count-based approach (COUNT(*) on today's incidents
 * + 1) rather than a DB sequence. This is documented in the source as a known race
 * condition at the generator level: "concurrent inserts race on the same count."
 * The UNIQUE constraint on incident_id is the safety net.
 *
 * This test verifies:
 * 1. Under modest concurrency (N=5 threads), all created incidents get UNIQUE incidentIds
 *    (the UNIQUE constraint rolls back duplicates and the application retries or errors cleanly).
 * 2. If some requests fail with a 5xx due to the duplicate constraint, the surviving
 *    requests all have unique IDs — no data corruption.
 * 3. The test deliberately exercises the race and documents the behavior, not asserts
 *    that all N succeed.
 *
 * NOTE: This test intentionally exercises a documented design limitation. The comments
 * in IncidentIdGenerator advise that production scale should use a DB sequence.
 * See OPEN-Q in the unit test report for the recommendation to the Developer.
 */
@Tag("AC-1")
@DisplayName("Concurrency: incident ID uniqueness under concurrent POST /incidents")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class IncidentIdConcurrencyTest {

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

    /**
     * Fires 5 concurrent POST /incidents requests, collects the successful responses,
     * and asserts that all successful incidentIds are unique.
     *
     * Known behavior: due to the count-based ID generator, some requests may fail with
     * a database unique constraint violation (exposed as 5xx). This is a documented
     * design trade-off (see IncidentIdGenerator source). The test asserts that:
     * - At least one request succeeds.
     * - All successful requests have distinct incidentIds matching INC-YYYYMMDD-NNNN.
     * - No two successful requests return the same incidentId.
     */
    @Test
    @Tag("AC-1")
    @DisplayName("Concurrency AC-1: 5 concurrent POST /incidents all produce unique incidentIds among successes")
    void concurrentCreate_allSuccessfulIncidentIdsAreUnique() throws Exception {
        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CopyOnWriteArrayList<String> successfulIncidentIds = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Integer> responseStatuses = new CopyOnWriteArrayList<>();

        String requestBody = """
                {
                  "title": "Concurrent Incident",
                  "description": "Testing concurrent ID generation",
                  "serviceIds": [],
                  "assets": []
                }
                """;

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String titleSuffix = String.valueOf(i);
            tasks.add(() -> {
                try {
                    MvcResult result = mockMvc.perform(post("/api/v1/incidents")
                                    .header("Authorization", "Bearer " + opsToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestBody))
                            .andReturn();

                    int httpStatus = result.getResponse().getStatus();
                    responseStatuses.add(httpStatus);

                    if (httpStatus == 201) {
                        JsonNode response = objectMapper.readTree(
                                result.getResponse().getContentAsString());
                        String incidentId = response.get("incidentId").asText();
                        successfulIncidentIds.add(incidentId);
                    }
                } catch (Exception e) {
                    // Some requests may fail with DB constraint — that's the point of the test
                    responseStatuses.add(500);
                }
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> future : futures) {
            future.get(); // propagate exceptions from tasks
        }
        executor.shutdown();

        // At least one request must have succeeded
        assertThat(successfulIncidentIds)
                .as("At least one concurrent incident creation must succeed")
                .isNotEmpty();

        // All successful incidentIds must be unique (no duplicates)
        Set<String> distinctIds = Set.copyOf(successfulIncidentIds);
        assertThat(successfulIncidentIds.size())
                .as("All successful incidentIds must be distinct — no duplicates permitted")
                .isEqualTo(distinctIds.size());

        // All successful incidentIds must match the format
        for (String incidentId : successfulIncidentIds) {
            assertThat(incidentId)
                    .as("incidentId must match INC-YYYYMMDD-NNNN format")
                    .matches("INC-\\d{8}-\\d{4}");
        }
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
