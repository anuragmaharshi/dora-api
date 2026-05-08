package com.dora.incidents.smoke;

import com.dora.dto.LoginRequest;
import com.dora.dto.LoginResponse;
import com.dora.incidents.application.ObjectStorageClient;
import com.dora.incidents.domain.AttachmentRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-8 regression: verifies BUG-2 is fixed.
 *
 * <p>BUG-2: completeAttachment() called markFailed()+save() before throwing
 * ResponseStatusException. Spring's default rollback-on-RuntimeException rolled back
 * the save(), so the attachment status stayed PENDING instead of FAILED.
 *
 * <p>Fix applied: {@code @Transactional(noRollbackFor = ResponseStatusException.class)}
 * on {@code completeAttachment()}. This test asserts that, after the 422 response, the
 * attachment row in the database carries status FAILED — not PENDING.
 *
 * <p>Requires a real DB (Testcontainers) because we need to verify the committed
 * DB state after the HTTP response. A mocked service would bypass the transaction
 * boundary and would not cover the bug scenario.
 */
@Tag("AC-8")
@DisplayName("AC-8: failed completeAttachment persists FAILED status")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class CompleteAttachmentFailedStatusTest {

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

    @Autowired
    AttachmentRepository attachmentRepository;

    @MockBean
    ObjectStorageClient objectStorageClient;

    private String opsToken;

    @BeforeEach
    void setUp() throws Exception {
        opsToken = loginAndGetToken("ops@dora.local", "ChangeMe!23");

        // Default stub for presignPut — needed so addAttachment does not NPE
        when(objectStorageClient.presignPut(any(String.class), any(Duration.class)))
                .thenAnswer(inv -> new URL("http://minio.local/dora-local/incidents/test-key"));
    }

    /**
     * When /complete is called and the object is NOT present in storage, the service
     * must:
     *   1. Return HTTP 422 to the caller.
     *   2. Persist status = FAILED in the DB (the whole point of BUG-2 fix).
     *
     * Without the @Transactional(noRollbackFor=ResponseStatusException.class) fix, step 2
     * would fail — the DB row would still show PENDING after the 422.
     */
    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: /complete returns 422 AND persists FAILED status when object absent from storage")
    void completeAttachment_objectMissingInStorage_returns422AndPersistsFailedStatus()
            throws Exception {

        // Arrange: create an incident and request a presigned URL (persists PENDING attachment)
        String incidentId = createIncident();
        UUID attachmentId = requestAttachmentUpload(incidentId);

        // Storage client reports object does NOT exist — simulates failed upload
        when(objectStorageClient.exists(any(String.class))).thenReturn(false);

        // Act: call /complete — expect 422
        mockMvc.perform(
                        post("/api/v1/incidents/" + incidentId
                                + "/attachments/" + attachmentId + "/complete")
                                .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isUnprocessableEntity());

        // Assert: the DB row must be FAILED, NOT PENDING.
        // This is the regression assertion for BUG-2: without the fix, findById returns
        // an attachment with status=PENDING because the markFailed()+save() was rolled back.
        String persistedStatus = attachmentRepository.findById(attachmentId)
                .map(a -> a.getStatus())
                .orElseThrow(() -> new AssertionError(
                        "Attachment " + attachmentId + " not found in DB after /complete call"));

        assertThat(persistedStatus)
                .as("Attachment status must be FAILED after a failed /complete — "
                        + "was PENDING before BUG-2 fix (markFailed() rolled back with exception)")
                .isEqualTo("FAILED");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String createIncident() throws Exception {
        String body = """
                {
                  "title": "BUG-2 Regression Incident",
                  "description": "Verifying FAILED status is committed on failed /complete",
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

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
    }

    private UUID requestAttachmentUpload(String incidentId) throws Exception {
        String body = """
                {
                  "filename": "evidence.pdf",
                  "contentType": "application/pdf",
                  "sizeBytes": 1048576
                }
                """;

        MvcResult result = mockMvc.perform(
                        post("/api/v1/incidents/" + incidentId + "/attachments")
                                .header("Authorization", "Bearer " + opsToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(response.get("attachmentId").asText());
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
