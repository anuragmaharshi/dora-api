package com.dora.thorough.admin;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-1 thorough validation tests: boundary and failure cases for PUT /api/v1/admin/tenant.
 *
 * <p>Covers cases missed by smoke tests: blank legalName, invalid LEI format variations,
 * invalid ncaEmail, invalid jurisdictionIso lengths, and nullable-field semantics.
 */
@Tag("AC-1")
@DisplayName("AC-1: TenantConfig — validation boundary tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class TenantConfigValidationTest {

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

    // ── legalName validation ────────────────────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: PUT with blank legalName returns 400 (@NotBlank constraint)")
    void putTenant_blankLegalName_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "  ",
                                  "lei": "NEXUSBNK000000000001"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: PUT with empty string legalName returns 400")
    void putTenant_emptyLegalName_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "",
                                  "lei": "NEXUSBNK000000000001"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: PUT with null legalName returns 400 (@NotBlank rejects null)")
    void putTenant_nullLegalName_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": null,
                                  "lei": "NEXUSBNK000000000001"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    // ── LEI format validation ───────────────────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: PUT with valid 20-char uppercase alphanumeric LEI returns 200")
    void putTenant_validLei_returns200() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "Nexus Bank",
                                  "lei": "NEXUSBNK000000000001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lei").value("NEXUSBNK000000000001"));
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: PUT with LEI containing lowercase letters returns 400 (must be uppercase)")
    void putTenant_leiWithLowercase_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "Nexus Bank",
                                  "lei": "nexusbnk000000000001"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: PUT with LEI of 19 chars returns 400 (must be exactly 20)")
    void putTenant_lei19Chars_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "Nexus Bank",
                                  "lei": "NEXUSBNK00000000001"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: PUT with LEI of 21 chars returns 400 (must be exactly 20)")
    void putTenant_lei21Chars_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "Nexus Bank",
                                  "lei": "NEXUSBNK0000000000001"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: PUT with LEI containing special chars returns 400")
    void putTenant_leiWithSpecialChars_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "Nexus Bank",
                                  "lei": "NEXUSBNK-00000000001"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: PUT with null LEI returns 200 (LEI is optional)")
    void putTenant_nullLei_returns200() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "Nexus Bank No LEI",
                                  "lei": null
                                }
                                """))
                .andExpect(status().isOk());
    }

    // ── ncaEmail validation ─────────────────────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: PUT with invalid ncaEmail format returns 400")
    void putTenant_invalidNcaEmail_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "Nexus Bank",
                                  "ncaEmail": "not-an-email"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: PUT with valid ncaEmail returns 200 and persists email")
    void putTenant_validNcaEmail_returns200WithEmail() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "Nexus Bank",
                                  "ncaEmail": "dora@centralbank.ie"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ncaEmail").value("dora@centralbank.ie"));
    }

    // ── jurisdictionIso validation ──────────────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: PUT with jurisdictionIso = exactly 2 chars returns 200")
    void putTenant_jurisdictionIso2Chars_returns200() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "Nexus Bank",
                                  "jurisdictionIso": "IE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jurisdictionIso").value("IE"));
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: PUT with jurisdictionIso = 1 char returns 400 (@Size min=2)")
    void putTenant_jurisdictionIso1Char_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "Nexus Bank",
                                  "jurisdictionIso": "I"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: PUT with jurisdictionIso = 3 chars returns 400 (@Size max=2)")
    void putTenant_jurisdictionIso3Chars_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "Nexus Bank",
                                  "jurisdictionIso": "IRL"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: PUT with null jurisdictionIso returns 200 (field is optional)")
    void putTenant_nullJurisdictionIso_returns200() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "Nexus Bank",
                                  "jurisdictionIso": null
                                }
                                """))
                .andExpect(status().isOk());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

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
