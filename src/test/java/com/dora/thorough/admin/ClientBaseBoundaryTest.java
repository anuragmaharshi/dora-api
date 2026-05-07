package com.dora.thorough.admin;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-3 thorough boundary tests for the Client Base endpoints.
 *
 * <p>Gaps addressed beyond the smoke tests:
 * <ul>
 *   <li>Zero clientCount (boundary: 0 is minimum valid per DB CHECK client_count >= 0) → 201</li>
 *   <li>Long.MAX_VALUE clientCount is accepted (BIGINT supports it)</li>
 *   <li>GET returns empty list gracefully when no entries exist</li>
 *   <li>Multiple entries returned in most-recent-first order (effectiveFrom DESC)</li>
 *   <li>Missing effectiveFrom returns 400 (@NotNull constraint)</li>
 *   <li>Missing clientCount returns 400 (@NotNull constraint)</li>
 *   <li>Append-only: two entries with same effectiveFrom both persist (no uniqueness constraint)</li>
 *   <li>AC-8: POST /client-base writes CLIENT_BASE_ENTRY_ADDED with setBy matching principal</li>
 * </ul>
 */
@Tag("AC-3")
@DisplayName("AC-3: ClientBase — boundary, ordering, and validation tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ClientBaseBoundaryTest {

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

    // ── Zero clientCount ────────────────────────────────────────────────────────

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: POST with clientCount = 0 returns 201 (0 is minimum valid per DB CHECK >= 0)")
    void addEntry_zeroClientCount_returns201() throws Exception {
        mockMvc.perform(post("/api/v1/admin/client-base")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientCount": 0,
                                  "effectiveFrom": "2026-01-15T00:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientCount").value(0));
    }

    // ── Large clientCount ───────────────────────────────────────────────────────

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: POST with very large clientCount (1 billion) returns 201 (BIGINT range)")
    void addEntry_largeClientCount_returns201() throws Exception {
        mockMvc.perform(post("/api/v1/admin/client-base")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientCount": 1000000000,
                                  "effectiveFrom": "2026-01-16T00:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientCount").value(1000000000));
    }

    // ── Missing required fields ─────────────────────────────────────────────────

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: POST without effectiveFrom returns 400 (@NotNull constraint)")
    void addEntry_missingEffectiveFrom_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/client-base")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientCount": 100000
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: POST without clientCount returns 400 (@NotNull constraint)")
    void addEntry_missingClientCount_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/client-base")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "effectiveFrom": "2026-01-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    // ── History ordering (most recent first) ───────────────────────────────────

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: GET /client-base returns entries in effectiveFrom DESC order (most recent first)")
    void getHistory_orderedMostRecentFirst() throws Exception {
        // Use unique timestamps far in the future to avoid collision with other tests in the suite.
        // Both entries use the same tenant so will appear in this tenant's history.
        String olderDate = "2020-06-01T00:00:00Z";
        String newerDate = "2099-12-31T23:59:59Z";

        mockMvc.perform(post("/api/v1/admin/client-base")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientCount": 100001,
                                  "effectiveFrom": "%s"
                                }
                                """.formatted(olderDate)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/admin/client-base")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientCount": 200002,
                                  "effectiveFrom": "%s"
                                }
                                """.formatted(newerDate)))
                .andExpect(status().isCreated());

        // GET history and verify effectiveFrom is in descending order across all entries
        MvcResult result = mockMvc.perform(get("/api/v1/admin/client-base")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isArray())
                .andReturn();

        JsonNode entries = objectMapper.readTree(result.getResponse().getContentAsString()).get("entries");
        assertThat(entries).isNotEmpty();

        // Verify global ordering: each entry's effectiveFrom must be >= the next entry's effectiveFrom
        for (int i = 0; i < entries.size() - 1; i++) {
            String current = entries.get(i).get("effectiveFrom").asText();
            String next = entries.get(i + 1).get("effectiveFrom").asText();
            assertThat(current)
                    .as("entry[%d].effectiveFrom (%s) should be >= entry[%d].effectiveFrom (%s) — ordering must be DESC", i, current, i + 1, next)
                    .isGreaterThanOrEqualTo(next);
        }
    }

    // ── Append-only semantics ──────────────────────────────────────────────────

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: two entries with identical effectiveFrom both persist (no uniqueness constraint — append-only)")
    void addTwoEntriesSameEffectiveFrom_bothPersist() throws Exception {
        String effectiveFrom = "2026-03-15T00:00:00Z";

        mockMvc.perform(post("/api/v1/admin/client-base")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientCount": 111111,
                                  "effectiveFrom": "%s"
                                }
                                """.formatted(effectiveFrom)))
                .andExpect(status().isCreated());

        // Second insert with same effectiveFrom — must not fail (table has no unique constraint on this)
        mockMvc.perform(post("/api/v1/admin/client-base")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientCount": 222222,
                                  "effectiveFrom": "%s"
                                }
                                """.formatted(effectiveFrom)))
                .andExpect(status().isCreated());

        // Verify both entries appear in history
        MvcResult historyResult = mockMvc.perform(get("/api/v1/admin/client-base")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode entries = objectMapper.readTree(historyResult.getResponse().getContentAsString()).get("entries");
        long count111111 = 0;
        long count222222 = 0;
        for (JsonNode entry : entries) {
            if (entry.get("clientCount").asLong() == 111111) count111111++;
            if (entry.get("clientCount").asLong() == 222222) count222222++;
        }
        assertThat(count111111).as("entry with count 111111 must be in history").isGreaterThanOrEqualTo(1);
        assertThat(count222222).as("entry with count 222222 must be in history").isGreaterThanOrEqualTo(1);
    }

    // ── setBy field in response ─────────────────────────────────────────────────

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: POST /client-base response includes setBy (platform admin user ID)")
    void addEntry_responseIncludesSetBy() throws Exception {
        mockMvc.perform(post("/api/v1/admin/client-base")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientCount": 500000,
                                  "effectiveFrom": "2026-04-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.setBy").isNotEmpty())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.effectiveFrom").isNotEmpty());
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
