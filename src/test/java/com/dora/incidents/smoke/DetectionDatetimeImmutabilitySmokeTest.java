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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-2: Detection datetime is server-stamped and never accepted from client input.
 *
 * <p>Verifies that:
 * 1. The response always carries a non-null detectionDatetime even though the client
 *    never provides one.
 * 2. A GET after creation returns the same immutable detectionDatetime.
 *
 * <p>The DB-level trigger test (raw SQL UPDATE → exception) belongs in thorough tests.
 * This smoke test covers the app-layer behaviour: field is absent in the request, present in
 * the response, and unchanged after a subsequent GET.
 */
@Tag("AC-2")
@DisplayName("AC-2: detection_datetime is server-stamped, immutable in responses")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class DetectionDatetimeImmutabilitySmokeTest {

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

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: response contains non-null detectionDatetime even though client did not supply one")
    void createIncident_detectionDatetimeIsServerSet() throws Exception {
        // Deliberately no detectionDatetime in request body
        String body = """
                {
                  "title": "Immutability Test Incident",
                  "description": "Testing detection datetime is server-stamped",
                  "serviceIds": [],
                  "assets": []
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String detectionDatetime = created.get("detectionDatetime").asText();
        String incidentUuid = created.get("id").asText();

        // Must be non-null / non-empty — server always stamps it
        assertThat(detectionDatetime).isNotBlank();

        // GET the same incident and confirm the same detectionDatetime is returned
        MvcResult getResult = mockMvc.perform(get("/api/v1/incidents/" + incidentUuid)
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode fetched = objectMapper.readTree(getResult.getResponse().getContentAsString());
        assertThat(fetched.get("detectionDatetime").asText()).isEqualTo(detectionDatetime);
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
