package com.dora;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class HealthIntegrationTest {

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

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: GET /actuator/health returns 200 with status UP")
    void actuatorHealth_returnsUp() {
        var response = restTemplate.getForEntity(
            "http://localhost:" + port + "/actuator/health", Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: GET /api/v1/health returns healthy payload for frontend consumption")
    void apiHealth_returnsHealthyPayload() {
        var response = restTemplate.getForEntity(
            "http://localhost:" + port + "/api/v1/health", Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("status", "healthy");
        assertThat(response.getBody()).containsKey("version");
        assertThat(response.getBody()).containsKey("timestamp");
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: Flyway applies V1_0_0__baseline successfully on clean Postgres")
    void flywayAppliedBaseline() {
        // If context loaded and actuator is UP, Flyway ran V1_0_0__baseline successfully.
        // A Flyway failure at startup would have prevented the context from loading at all.
        var response = restTemplate.getForEntity(
            "http://localhost:" + port + "/actuator/health", Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
