package com.dora.smoke.audit;

import com.dora.dto.AuditEntry;
import com.dora.services.AuditService;
import com.dora.services.audit.AuditAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-1 smoke test: AuditService.record() + SELECT round-trip.
 *
 * <p>Given any service calls AuditService.record(...),
 * When the containing transaction commits,
 * Then a row appears in audit_log with the correct fields.
 *
 * <p>Uses @SpringBootTest + Testcontainers so Flyway runs the real migration
 * (including the immutability trigger) and all JPA plumbing is exercised.
 */
@Tag("AC-1")
@DisplayName("AC-1: AuditService.record() persists a row and findByEntity() returns it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class AuditServiceRoundTripSmokeTest {

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
    AuditService auditService;

    @Test
    @DisplayName("AC-1: record() writes a row that findByEntity() returns with correct fields")
    void record_thenFindByEntity_returnsRow() {
        UUID entityId = UUID.randomUUID();
        String entityType = "PROBE";

        // No security context in this test: actor will be resolved as SYSTEM
        auditService.record(AuditAction.SYSTEM, entityType, entityId, null, null);

        Page<AuditEntry> page = auditService.findByEntity(entityType, entityId, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1L);

        AuditEntry entry = page.getContent().get(0);
        assertThat(entry.action()).isEqualTo("SYSTEM");
        assertThat(entry.entityType()).isEqualTo("PROBE");
        assertThat(entry.entityId()).isEqualTo(entityId);
        assertThat(entry.actorUsername()).isEqualTo("SYSTEM");
        // No authenticated context → actorId is null
        assertThat(entry.actorId()).isNull();
        assertThat(entry.createdAt()).isNotNull();
        // context must have the three keys (Q-2 contract)
        assertThat(entry.context()).isNotNull();
        assertThat(entry.context().has("request_id")).isTrue();
        assertThat(entry.context().has("remote_ip")).isTrue();
        assertThat(entry.context().has("user_agent")).isTrue();
    }
}
