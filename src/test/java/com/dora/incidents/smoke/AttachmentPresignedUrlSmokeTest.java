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

import java.net.URL;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-3: Attachment presigned upload URL smoke test.
 *
 * <p>Verifies that:
 * 1. POST /incidents/{id}/attachments returns 201 with attachmentId and uploadUrl
 * 2. POST /incidents/{id}/attachments/{id}/complete returns 200 with status READY
 *    when the storage client reports the object exists.
 * 3. Size limit enforcement: request exceeding max-mb returns 422.
 *
 * <p>ObjectStorageClient is mocked — no MinIO needed for this AC smoke test.
 */
@Tag("AC-3")
@DisplayName("AC-3: Attachment presigned URL — metadata persisted, status transitions")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AttachmentPresignedUrlSmokeTest {

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

    @MockBean
    ObjectStorageClient objectStorageClient;

    private String opsToken;

    @BeforeEach
    void setUp() throws Exception {
        opsToken = loginAndGetToken("ops@dora.local", "ChangeMe!23");

        // Default stub: presign returns a dummy URL
        when(objectStorageClient.presignPut(any(String.class), any(Duration.class)))
                .thenAnswer(inv -> new URL("http://minio.local/dora-local/incidents/test-key"));
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: POST /attachments returns 201 with attachmentId and uploadUrl")
    void requestAttachmentUpload_returns201WithPresignedUrl() throws Exception {
        String incidentId = createIncident();

        String body = """
                {
                  "filename": "evidence.pdf",
                  "contentType": "application/pdf",
                  "sizeBytes": 1048576
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/attachments")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachmentId").isNotEmpty())
                .andExpect(jsonPath("$.uploadUrl").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.get("attachmentId").asText()).isNotBlank();
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: POST /complete flips status to READY when object exists in storage")
    void completeAttachment_setsStatusReady_whenObjectExists() throws Exception {
        String incidentId = createIncident();

        // Request a presigned URL (persists PENDING attachment)
        String uploadBody = """
                {
                  "filename": "screenshot.png",
                  "contentType": "image/png",
                  "sizeBytes": 512000
                }
                """;

        MvcResult uploadResult = mockMvc.perform(
                        post("/api/v1/incidents/" + incidentId + "/attachments")
                                .header("Authorization", "Bearer " + opsToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(uploadBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode uploadResponse = objectMapper.readTree(uploadResult.getResponse().getContentAsString());
        String attachmentId = uploadResponse.get("attachmentId").asText();

        // Mock: object exists in storage after "client upload"
        when(objectStorageClient.exists(any(String.class))).thenReturn(true);

        mockMvc.perform(
                        post("/api/v1/incidents/" + incidentId + "/attachments/" + attachmentId + "/complete")
                                .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.id").value(attachmentId));
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: POST /attachments with file size exceeding limit returns 422")
    void requestAttachmentUpload_oversizedFile_returns422() throws Exception {
        String incidentId = createIncident();

        // 11 MB — exceeds the 10 MB cap configured in DynamicPropertySource
        long oversized = 11L * 1024 * 1024;
        String body = String.format("""
                {
                  "filename": "huge.zip",
                  "contentType": "application/zip",
                  "sizeBytes": %d
                }
                """, oversized);

        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/attachments")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String createIncident() throws Exception {
        String body = """
                {
                  "title": "Attachment Test Incident",
                  "description": "Testing attachment upload flow",
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
