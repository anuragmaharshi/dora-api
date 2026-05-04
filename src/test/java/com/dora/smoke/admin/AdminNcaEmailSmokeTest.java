package com.dora.smoke.admin;

import com.dora.dto.LoginRequest;
import com.dora.dto.LoginResponse;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-4 smoke tests: NCA email config GET and PUT.
 */
@Tag("AC-4")
@DisplayName("AC-4: NCA Email Config — GET/PUT /api/v1/admin/nca-email")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AdminNcaEmailSmokeTest {

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
    @Tag("AC-4")
    @DisplayName("AC-4: GET /api/v1/admin/nca-email returns 200 (empty if not configured)")
    void getNcaEmailConfig_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/admin/nca-email")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk());
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: PUT /api/v1/admin/nca-email creates/updates config and returns 200")
    void putNcaEmailConfig_returns200WithPersistedValues() throws Exception {
        String body = """
                {
                  "sender": "dora-system@nexusbank.com",
                  "recipient": "dora.reports@centralbank.ie",
                  "subjectTemplate": "DORA Incident Report — {incidentId} — {date}"
                }
                """;

        mockMvc.perform(put("/api/v1/admin/nca-email")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sender").value("dora-system@nexusbank.com"))
                .andExpect(jsonPath("$.recipient").value("dora.reports@centralbank.ie"))
                .andExpect(jsonPath("$.subjectTemplate").value("DORA Incident Report — {incidentId} — {date}"));
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: PUT with invalid sender email returns 400")
    void putNcaEmailConfig_invalidEmail_returns400() throws Exception {
        String body = """
                {
                  "sender": "not-an-email",
                  "recipient": "valid@example.com",
                  "subjectTemplate": "Subject"
                }
                """;

        mockMvc.perform(put("/api/v1/admin/nca-email")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: second PUT updates the existing record (upsert semantics)")
    void putNcaEmailConfig_twice_updatesRecord() throws Exception {
        String first = """
                {
                  "sender": "first@nexusbank.com",
                  "recipient": "nca@regulator.eu",
                  "subjectTemplate": "First template"
                }
                """;
        mockMvc.perform(put("/api/v1/admin/nca-email")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first))
                .andExpect(status().isOk());

        String second = """
                {
                  "sender": "updated@nexusbank.com",
                  "recipient": "nca@regulator.eu",
                  "subjectTemplate": "Updated template"
                }
                """;
        mockMvc.perform(put("/api/v1/admin/nca-email")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sender").value("updated@nexusbank.com"))
                .andExpect(jsonPath("$.subjectTemplate").value("Updated template"));
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
