package com.dora.thorough;

import com.dora.config.JacksonConfig;
import com.dora.controllers.HealthController;
import com.dora.security.SecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Thorough boundary and contract tests for HealthController.
 * Covers AC-2 and AC-5 edge cases not addressed by the smoke test.
 */
@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, JacksonConfig.class})
@DisplayName("HealthController — thorough tests")
class HealthControllerThoroughTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ------------------------------------------------------------------ AC-5

    @Test
    @Tag("AC-5")
    @DisplayName("Response Content-Type is application/json")
    void health_contentTypeIsApplicationJson() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @Tag("AC-5")
    @DisplayName("status field is exactly 'healthy' — not null, not empty, not whitespace")
    void health_statusFieldIsExactlyHealthy() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("healthy"));
    }

    @Test
    @Tag("AC-5")
    @DisplayName("timestamp field is a valid ISO-8601 instant parseable by Instant.parse()")
    void health_timestampIsValidIso8601Instant() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(body);
        String timestampValue = root.get("timestamp").asText();

        assertThatCode(() -> Instant.parse(timestampValue))
                .as("timestamp '%s' must be parseable as ISO-8601 instant", timestampValue)
                .doesNotThrowAnyException();
    }

    @Test
    @Tag("AC-5")
    @DisplayName("version field is non-null and non-empty")
    void health_versionFieldIsNonNullAndNonEmpty() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(body);
        String version = root.get("version").asText();

        assertThat(version)
                .as("version must be non-null and non-empty")
                .isNotNull()
                .isNotBlank();
    }

    @Test
    @Tag("AC-5")
    @DisplayName("Response has no extra top-level fields beyond status, version, timestamp")
    void health_noUnexpectedTopLevelFields() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(body);

        Set<String> allowedFields = Set.of("status", "version", "timestamp");
        root.fieldNames().forEachRemaining(field ->
                assertThat(allowedFields)
                        .as("Unexpected top-level field '%s' found in response", field)
                        .contains(field)
        );
    }

    // ------------------------------------------------------------------ AC-2 / security

    @Test
    @Tag("AC-2")
    @DisplayName("GET /api/v1/health succeeds without any Authorization header")
    void health_noAuthHeaderReturns200() throws Exception {
        // No Authorization header — must still be 200 (LLD-01 is permissive; auth arrives in LLD-02)
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk());
    }

    @Test
    @Tag("AC-2")
    @DisplayName("GET /api/v1/health with a fake Bearer token still returns 200 — no auth enforcement in LLD-01")
    void health_fakeBearerTokenReturns200() throws Exception {
        mockMvc.perform(get("/api/v1/health")
                        .header("Authorization", "Bearer fake-token-should-be-ignored"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ failure mode

    @Test
    @Tag("AC-5")
    @DisplayName("POST /api/v1/health returns 405 Method Not Allowed — endpoint is GET-only")
    void health_postReturns405() throws Exception {
        mockMvc.perform(post("/api/v1/health"))
                .andExpect(status().isMethodNotAllowed());
    }
}
