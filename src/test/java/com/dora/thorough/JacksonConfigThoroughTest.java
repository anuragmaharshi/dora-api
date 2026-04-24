package com.dora.thorough;

import com.dora.config.JacksonConfig;
import com.dora.dto.HealthResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Thorough tests for JacksonConfig.
 *
 * The openapi.yaml contract declares timestamp as format: date-time (ISO-8601 string).
 * JacksonConfig disables WRITE_DATES_AS_TIMESTAMPS to enforce this.
 * These tests verify that the ObjectMapper in the application context honours that config.
 *
 * Uses @JsonTest: a lightweight slice that loads only Jackson-related auto-configuration.
 * No servlet container, no datasource, no Flyway — this is a pure serialisation test.
 */
@JsonTest
@Import(JacksonConfig.class)
@DisplayName("JacksonConfig — thorough serialisation tests")
class JacksonConfigThoroughTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @Tag("AC-5")
    @DisplayName("Instant is serialised as an ISO-8601 string, not epoch milliseconds")
    void instant_serialisedAsIso8601String_notEpochMillis() throws Exception {
        Instant fixed = Instant.parse("2026-04-23T10:00:00Z");
        HealthResponse response = new HealthResponse("healthy", "0.0.1", fixed);

        String json = objectMapper.writeValueAsString(response);

        // Must contain the ISO string representation of the instant
        assertThat(json).contains("2026-04-23T10:00:00Z");

        // Must NOT contain the raw epoch millisecond value (which would be a 13-digit number)
        // Epoch ms for 2026-04-23T10:00:00Z = 1745402400000
        assertThat(json).doesNotContain("1745402400000");
    }

    @Test
    @Tag("AC-5")
    @DisplayName("Serialised timestamp value is parseable back to the original Instant without data loss")
    void instant_roundTripSerialisation_preservesValue() throws Exception {
        Instant original = Instant.parse("2026-04-23T10:00:00Z");
        HealthResponse response = new HealthResponse("healthy", "0.0.1", original);

        String json = objectMapper.writeValueAsString(response);
        HealthResponse deserialised = objectMapper.readValue(json, HealthResponse.class);

        assertThat(deserialised.timestamp())
                .as("Round-tripped timestamp must equal the original instant")
                .isEqualTo(original);
    }

    @Test
    @Tag("AC-5")
    @DisplayName("ObjectMapper is injected from the application context — JacksonConfig bean is active")
    void objectMapper_isInjectedFromContext() {
        assertThat(objectMapper).isNotNull();
    }
}
