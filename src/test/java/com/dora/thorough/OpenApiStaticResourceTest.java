package com.dora.thorough;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests verifying that static OpenAPI resources and Swagger UI
 * are served correctly by the running application.
 *
 * Covers AC-5 (Swagger UI loads and serves the hand-written openapi.yaml contract).
 *
 * Uses a shared static PostgreSQL container (same pattern as HealthIntegrationTest)
 * because the full application context — including Flyway — must boot.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("OpenAPI static resources — thorough integration tests")
class OpenApiStaticResourceTest {

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

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    // ------------------------------------------------------------------ AC-5

    @Test
    @Tag("AC-5")
    @DisplayName("GET /openapi.yaml returns 200 — static YAML contract is served")
    void openApiYaml_returns200() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/openapi.yaml", String.class);

        assertThat(response.getStatusCode())
                .as("GET /openapi.yaml should return 200 OK")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @Tag("AC-5")
    @DisplayName("GET /openapi.yaml Content-Type is a text type — YAML served as text/plain, text/yaml, or application/yaml")
    void openApiYaml_contentTypeIsTextBased() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/openapi.yaml", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String contentType = response.getHeaders().getContentType() != null
                ? response.getHeaders().getContentType().toString()
                : "";

        // Spring Boot serves static YAML files as text/plain by default.
        // Accept any text type or application/yaml — all are valid for a YAML static resource.
        assertThat(contentType)
                .as("Content-Type must be a text-based type for YAML, but was: '%s'", contentType)
                .satisfiesAnyOf(
                        ct -> assertThat(ct).containsIgnoringCase("text/"),
                        ct -> assertThat(ct).containsIgnoringCase("application/yaml"),
                        ct -> assertThat(ct).containsIgnoringCase("application/x-yaml")
                );
    }

    @Test
    @Tag("AC-5")
    @DisplayName("GET /openapi.yaml body contains the openapi version declaration")
    void openApiYaml_bodyContainsOpenApiDeclaration() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/openapi.yaml", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("openapi.yaml body must contain the 'openapi:' version key")
                .contains("openapi:");
    }

    @Test
    @Tag("AC-5")
    @DisplayName("GET /swagger-ui.html returns 200 — Swagger UI is accessible")
    void swaggerUi_returns200() {
        // Spring Boot / springdoc will redirect /swagger-ui.html to the actual UI path.
        // TestRestTemplate follows redirects by default.
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/swagger-ui.html", String.class);

        assertThat(response.getStatusCode())
                .as("GET /swagger-ui.html should return 200 OK (possibly after redirect)")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @Tag("AC-5")
    @DisplayName("GET /v3/api-docs returns 200 with JSON content — springdoc api-docs endpoint is active")
    void apiDocs_returns200WithJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/v3/api-docs", String.class);

        assertThat(response.getStatusCode())
                .as("GET /v3/api-docs should return 200 OK")
                .isEqualTo(HttpStatus.OK);

        String contentType = response.getHeaders().getContentType() != null
                ? response.getHeaders().getContentType().toString()
                : "";

        assertThat(contentType)
                .as("api-docs Content-Type must be application/json, but was: '%s'", contentType)
                .containsIgnoringCase("application/json");
    }
}
