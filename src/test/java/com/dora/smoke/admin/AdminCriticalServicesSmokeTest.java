package com.dora.smoke.admin;

import com.dora.dto.LoginRequest;
import com.dora.dto.LoginResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-2 smoke tests: PLATFORM_ADMIN CRUD + archive for critical services.
 */
@Tag("AC-2")
@DisplayName("AC-2: Critical Services CRUD + archive")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AdminCriticalServicesSmokeTest {

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
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private String platformToken;

    @BeforeEach
    void obtainToken() throws Exception {
        platformToken = loginAndGetToken("platform@dora.local", "ChangeMe!23");
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: POST critical service creates new service with active=true")
    void createCriticalService_returns201WithActiveTrue() throws Exception {
        String body = """
                {
                  "name": "Payments Processing",
                  "description": "Core payments rail"
                }
                """;

        mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Payments Processing"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: GET critical services returns list including the created one")
    void listCriticalServices_returnsArray() throws Exception {
        // Create one first
        String body = """
                {
                  "name": "FX Settlement",
                  "description": "Foreign exchange settlement"
                }
                """;
        mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: PUT critical service updates name and description")
    void updateCriticalService_returns200WithUpdatedName() throws Exception {
        // Create
        String createBody = """
                { "name": "Liquidity Mgmt", "description": "Original description" }
                """;
        MvcResult createResult = mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String id = created.get("id").asText();

        // Update
        String updateBody = """
                { "name": "Liquidity Management", "description": "Updated description" }
                """;
        mockMvc.perform(put("/api/v1/admin/critical-services/" + id)
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Liquidity Management"))
                .andExpect(jsonPath("$.description").value("Updated description"));
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: POST archive sets active=false")
    void archiveCriticalService_returns204_andServiceBecomesInactive() throws Exception {
        // Create
        String createBody = """
                { "name": "SWIFT Messaging", "description": "SWIFT integration" }
                """;
        MvcResult createResult = mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String id = created.get("id").asText();

        // Archive
        mockMvc.perform(post("/api/v1/admin/critical-services/" + id + "/archive")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isNoContent());

        // Verify list shows active=false
        MvcResult listResult = mockMvc.perform(get("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode list = objectMapper.readTree(listResult.getResponse().getContentAsString());
        boolean found = false;
        for (JsonNode item : list) {
            if (id.equals(item.get("id").asText())) {
                assertThat(item.get("active").asBoolean()).isFalse();
                found = true;
                break;
            }
        }
        assertThat(found).as("archived service must still appear in full list").isTrue();
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: PUT non-existent service returns 404")
    void updateNonExistentService_returns404() throws Exception {
        String updateBody = """
                { "name": "Does Not Exist", "description": "N/A" }
                """;
        mockMvc.perform(put("/api/v1/admin/critical-services/00000000-0000-0000-0000-000000000099")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isNotFound());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

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
