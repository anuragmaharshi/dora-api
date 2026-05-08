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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-8: PLATFORM_ADMIN must receive HTTP 403 on all incident endpoints (BR-011, NFR-009).
 *
 * <p>The PlatformAdminFirewallFilter blocks PLATFORM_ADMIN on non-admin paths.
 * Additionally, @PreAuthorize on the controller methods excludes PLATFORM_ADMIN by omission.
 * This test verifies both layers work together for incident endpoints.
 */
@Tag("AC-8")
@DisplayName("AC-8: PLATFORM_ADMIN receives 403 on all incident endpoints")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class PlatformAdminBlockedSmokeTest {

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

    private String platformAdminToken;

    @BeforeEach
    void obtainToken() throws Exception {
        platformAdminToken = loginAndGetToken("platform@dora.local", "ChangeMe!23");
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: PLATFORM_ADMIN POST /incidents returns 403")
    void platformAdmin_postIncidents_returns403() throws Exception {
        String body = """
                {
                  "title": "Blocked Incident",
                  "description": "PLATFORM_ADMIN should not create incidents",
                  "serviceIds": [],
                  "assets": []
                }
                """;

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + platformAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: PLATFORM_ADMIN GET /incidents returns 403")
    void platformAdmin_getIncidents_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/incidents")
                        .header("Authorization", "Bearer " + platformAdminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: PLATFORM_ADMIN GET /incidents/{id} returns 403")
    void platformAdmin_getIncidentById_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/incidents/00000000-0000-0000-0000-000000000001")
                        .header("Authorization", "Bearer " + platformAdminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: unauthenticated request to GET /incidents returns 401")
    void unauthenticated_getIncidents_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isUnauthorized());
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
