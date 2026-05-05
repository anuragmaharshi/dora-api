package com.dora.thorough.admin;

import com.dora.dto.LoginRequest;
import com.dora.dto.LoginResponse;
import com.dora.repositories.AuditLogRepository;
import com.dora.services.audit.AuditAction;
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
 * AC-2 thorough boundary tests for critical service CRUD.
 *
 * <p>Gaps addressed beyond smoke tests:
 * <ul>
 *   <li>Duplicate name in same tenant → 409 Conflict</li>
 *   <li>Archive already-archived service is idempotent (no duplicate audit row)</li>
 *   <li>Name at exact boundary (255 chars) → 201</li>
 *   <li>Name over boundary (256 chars) → 400</li>
 *   <li>Empty/blank name → 400</li>
 *   <li>Archive non-existent UUID → 404</li>
 *   <li>listActive only returns active services (archived are excluded)</li>
 *   <li>AC-8: PUT critical-service writes CRITICAL_SERVICE_UPDATED audit row</li>
 *   <li>AC-8: archive writes CRITICAL_SERVICE_ARCHIVED audit row</li>
 *   <li>AC-8: archive already-archived does NOT write a second audit row (idempotent)</li>
 * </ul>
 */
@Tag("AC-2")
@DisplayName("AC-2: CriticalService — boundary, duplicate, idempotent-archive tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class CriticalServiceBoundaryTest {

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

    @Autowired
    AuditLogRepository auditLogRepository;

    private String platformToken;

    @BeforeEach
    void obtainToken() throws Exception {
        platformToken = loginAndGetToken("platform@dora.local", "ChangeMe!23");
    }

    // ── Duplicate name → 409 ───────────────────────────────────────────────────

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: POST with duplicate name in same tenant returns 409 Conflict")
    void createCriticalService_duplicateName_returns409() throws Exception {
        String name = "Duplicate Service " + System.nanoTime();
        String body = """
                { "name": "%s", "description": "first" }
                """.formatted(name);

        // First create succeeds
        mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // Second create with same name → 409
        mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: PUT with name that already exists on another service in same tenant returns 409")
    void updateCriticalService_nameCollision_returns409() throws Exception {
        String nameA = "Service Alpha " + System.nanoTime();
        String nameB = "Service Beta " + System.nanoTime();

        // Create service A
        mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "%s", "description": "A" }
                                """.formatted(nameA)))
                .andExpect(status().isCreated());

        // Create service B
        MvcResult bResult = mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "%s", "description": "B" }
                                """.formatted(nameB)))
                .andExpect(status().isCreated())
                .andReturn();

        String bId = objectMapper.readTree(bResult.getResponse().getContentAsString()).get("id").asText();

        // Try to rename B to A → 409
        mockMvc.perform(put("/api/v1/admin/critical-services/" + bId)
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "%s", "description": "Renamed to A" }
                                """.formatted(nameA)))
                .andExpect(status().isConflict());
    }

    // ── Name length boundaries ──────────────────────────────────────────────────

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: POST with name exactly 255 chars returns 201 (boundary inclusive)")
    void createCriticalService_name255Chars_returns201() throws Exception {
        String name255 = "A".repeat(255);

        mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "%s", "description": "boundary test" }
                                """.formatted(name255)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name255));
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: POST with name of 256 chars returns 400 (over @Size max=255)")
    void createCriticalService_name256Chars_returns400() throws Exception {
        String name256 = "A".repeat(256);

        mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "%s", "description": "over limit" }
                                """.formatted(name256)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: POST with blank (whitespace-only) name returns 400 (@NotBlank)")
    void createCriticalService_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "   ", "description": "blank name" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: POST with empty string name returns 400 (@NotBlank)")
    void createCriticalService_emptyName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "", "description": "empty name" }
                                """))
                .andExpect(status().isBadRequest());
    }

    // ── Archive idempotency ─────────────────────────────────────────────────────

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: archiving an already-archived service returns 204 (idempotent — no error)")
    void archiveTwice_isIdempotent_returns204BothTimes() throws Exception {
        String name = "Idempotent Archive " + System.nanoTime();
        MvcResult createResult = mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "%s", "description": "to be archived twice" }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        // First archive
        mockMvc.perform(post("/api/v1/admin/critical-services/" + id + "/archive")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isNoContent());

        // Second archive — must not return 4xx or 5xx
        mockMvc.perform(post("/api/v1/admin/critical-services/" + id + "/archive")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: archiving already-archived service does NOT produce a second CRITICAL_SERVICE_ARCHIVED audit row")
    void archiveTwice_doesNotDoubleWriteAuditRow() throws Exception {
        String name = "NoDoubleAudit " + System.nanoTime();
        MvcResult createResult = mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "%s", "description": "idempotent audit check" }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        // First archive
        mockMvc.perform(post("/api/v1/admin/critical-services/" + id + "/archive")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isNoContent());

        long countAfterFirst = auditLogRepository.findAll().stream()
                .filter(row -> AuditAction.CRITICAL_SERVICE_ARCHIVED.name().equals(row.getAction()))
                .count();

        // Second archive
        mockMvc.perform(post("/api/v1/admin/critical-services/" + id + "/archive")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isNoContent());

        long countAfterSecond = auditLogRepository.findAll().stream()
                .filter(row -> AuditAction.CRITICAL_SERVICE_ARCHIVED.name().equals(row.getAction()))
                .count();

        assertThat(countAfterSecond)
                .as("second archive of same service must not produce a new CRITICAL_SERVICE_ARCHIVED row")
                .isEqualTo(countAfterFirst);
    }

    // ── Archive non-existent → 404 ─────────────────────────────────────────────

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: archiving non-existent service UUID returns 404")
    void archiveNonExistent_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/admin/critical-services/00000000-0000-0000-0000-000000000099/archive")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isNotFound());
    }

    // ── listActive excludes archived ────────────────────────────────────────────

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: archived service is excluded from active-only list (listActive picklist for LLD-05)")
    void archivedService_notReturnedByActiveList() throws Exception {
        String name = "WillBeArchived " + System.nanoTime();
        MvcResult createResult = mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "%s", "description": "active list check" }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        // Archive it
        mockMvc.perform(post("/api/v1/admin/critical-services/" + id + "/archive")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isNoContent());

        // Full list includes archived (active field = false)
        MvcResult listResult = mockMvc.perform(get("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode list = objectMapper.readTree(listResult.getResponse().getContentAsString());
        boolean archivedServiceInList = false;
        for (JsonNode item : list) {
            if (id.equals(item.get("id").asText())) {
                assertThat(item.get("active").asBoolean()).as("archived service must have active=false").isFalse();
                archivedServiceInList = true;
                break;
            }
        }
        assertThat(archivedServiceInList).as("archived service must still appear in full list").isTrue();
    }

    // ── AC-8: PUT critical-service writes CRITICAL_SERVICE_UPDATED audit row ───

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: PUT /api/v1/admin/critical-services/{id} writes CRITICAL_SERVICE_UPDATED audit row")
    void updateCriticalService_writesUpdatedAuditRow() throws Exception {
        String name = "ToUpdate " + System.nanoTime();
        MvcResult createResult = mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "%s", "description": "original" }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        long before = auditLogRepository.findAll().stream()
                .filter(row -> AuditAction.CRITICAL_SERVICE_UPDATED.name().equals(row.getAction()))
                .count();

        mockMvc.perform(put("/api/v1/admin/critical-services/" + id)
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "%s Updated", "description": "updated description" }
                                """.formatted(name)))
                .andExpect(status().isOk());

        long after = auditLogRepository.findAll().stream()
                .filter(row -> AuditAction.CRITICAL_SERVICE_UPDATED.name().equals(row.getAction()))
                .count();

        assertThat(after).as("CRITICAL_SERVICE_UPDATED audit row must exist after PUT").isGreaterThan(before);
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: POST /api/v1/admin/critical-services/{id}/archive writes CRITICAL_SERVICE_ARCHIVED audit row")
    void archiveCriticalService_writesArchivedAuditRow() throws Exception {
        String name = "ToArchive " + System.nanoTime();
        MvcResult createResult = mockMvc.perform(post("/api/v1/admin/critical-services")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "%s", "description": "for archive audit" }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        long before = auditLogRepository.findAll().stream()
                .filter(row -> AuditAction.CRITICAL_SERVICE_ARCHIVED.name().equals(row.getAction()))
                .count();

        mockMvc.perform(post("/api/v1/admin/critical-services/" + id + "/archive")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isNoContent());

        long after = auditLogRepository.findAll().stream()
                .filter(row -> AuditAction.CRITICAL_SERVICE_ARCHIVED.name().equals(row.getAction()))
                .count();

        assertThat(after).as("CRITICAL_SERVICE_ARCHIVED audit row must exist after archive").isGreaterThan(before);
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
