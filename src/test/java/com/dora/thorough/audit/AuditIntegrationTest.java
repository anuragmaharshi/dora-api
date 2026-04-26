package com.dora.thorough.audit;

import com.dora.dto.AuditEntry;
import com.dora.entities.AppUser;
import com.dora.entities.Tenant;
import com.dora.repositories.AuditLogRepository;
import com.dora.security.CustomUserDetails;
import com.dora.services.AuditService;
import com.dora.services.audit.AuditAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Full-stack integration tests for the audit trail (LLD-03 ACs 1–3).
 *
 * <p>Requires Docker. Uses {@code postgres:15-alpine} with Flyway migrations so the
 * immutability trigger ({@code audit_log_no_mutation}) is active.
 *
 * <p>All three production bugs found during LLD-03 thorough-test authoring were fixed in
 * dora-api PR #9:
 * <ul>
 *   <li>BUG-1: {@code @NoRepositoryBean} added to {@code AuditedRepository}.</li>
 *   <li>BUG-2: {@code tenant_id} made nullable via migration V1_2_1 — SYSTEM events work.</li>
 *   <li>BUG-3: {@code insertable = false} added to {@code AuditLog.createdAt} — DB DEFAULT
 *       now() is respected.</li>
 * </ul>
 */
@Tag("AC-1")
@DisplayName("Audit trail integration — requires Docker/Testcontainers")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class AuditIntegrationTest {

    /**
     * Spring-managed bean for AC-3 rollback helpers. Methods here are called via the Spring
     * proxy so @Transactional(REQUIRES_NEW) takes effect (self-invocation would bypass it).
     */
    @TestConfiguration
    static class RollbackHelperConfig {
        @Bean
        RollbackHelper rollbackHelper(AuditService auditService) {
            return new RollbackHelper(auditService);
        }
    }

    static class RollbackHelper {
        private final AuditService auditService;
        RollbackHelper(AuditService auditService) { this.auditService = auditService; }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void recordAndRollback(UUID entityId) {
            auditService.record(AuditAction.INCIDENT_CREATED, "INCIDENT", entityId, null, null);
            throw new RuntimeException("forced rollback for AC-3 test");
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void recordTwoAndRollback(UUID entityId1, UUID entityId2) {
            auditService.record(AuditAction.INCIDENT_CREATED, "INCIDENT", entityId1, null, null);
            auditService.record(AuditAction.INCIDENT_UPDATED, "INCIDENT", entityId2, null, null);
            throw new RuntimeException("forced rollback for AC-3 multi-row test");
        }
    }

    /** Tenant UUID seeded by Flyway V1_1_0 / V1_1_1 migrations. */
    private static final UUID SEEDED_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** OPS_ANALYST user seeded by V1_1_1 — satisfies fk_audit_log_actor FK. */
    private static final UUID SEEDED_USER_ID = UUID.fromString("00000000-0000-0000-0001-000000000002");

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

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    RollbackHelper rollbackHelper;

    @BeforeEach
    void setAuthenticatedContext() {
        Tenant tenant = Mockito.mock(Tenant.class);
        when(tenant.getId()).thenReturn(SEEDED_TENANT_ID);
        AppUser user = Mockito.mock(AppUser.class);
        when(user.getTenant()).thenReturn(tenant);
        when(user.getId()).thenReturn(SEEDED_USER_ID);
        when(user.getUsername()).thenReturn("integration-test@dora.local");
        CustomUserDetails details = new CustomUserDetails(user, List.of("OPS_ANALYST"));
        var auth = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        // TRUNCATE bypasses the row-level immutability trigger (BEFORE UPDATE/DELETE per row).
        // DELETE fires the trigger and would be rejected. TRUNCATE does not fire row-level triggers.
        jdbcTemplate.execute("TRUNCATE TABLE audit_log");
    }

    // ── BUG-2 regression: SYSTEM actor with null tenant_id ────────────────────

    /**
     * BUG-2 regression: V1_2_1 made tenant_id nullable so SYSTEM (unauthenticated) events
     * no longer violate the DB NOT NULL constraint.
     */
    @Test
    @Tag("AC-1")
    @DisplayName("BUG-2 fix: SYSTEM actor (no auth context) records successfully — tenant_id is nullable")
    void systemActor_nullTenantId_recordsSuccessfully() {
        SecurityContextHolder.clearContext();

        UUID entityId = UUID.randomUUID();
        auditService.record(AuditAction.SYSTEM, "PROBE", entityId, null, null);

        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ?", Integer.class, entityId);
        assertThat(count).as("SYSTEM audit row must be persisted with null tenant_id").isEqualTo(1);

        String tenantIdCol = jdbcTemplate.queryForObject(
                "SELECT tenant_id::text FROM audit_log WHERE entity_id = ?", String.class, entityId);
        assertThat(tenantIdCol).as("tenant_id must be NULL for SYSTEM events").isNull();
    }

    /**
     * BUG-3 regression: insertable=false on AuditLog.createdAt lets the DB DEFAULT now()
     * fire rather than Hibernate sending explicit NULL.
     */
    @Test
    @Tag("AC-1")
    @DisplayName("BUG-3 fix: AuditLog.createdAt is set by DB DEFAULT when insertable=false")
    void auditLog_createdAt_setByDbDefault() {
        UUID entityId = UUID.randomUUID();
        auditService.record(AuditAction.INCIDENT_CREATED, "INCIDENT", entityId, null, null);

        String createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at::text FROM audit_log WHERE entity_id = ?", String.class, entityId);
        assertThat(createdAt).as("created_at must be set by DB DEFAULT now()").isNotNull();
    }

    // ── AC-1: round-trip tests ────────────────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: record() persists row; findByEntity() returns it with correct fields")
    void record_thenFindByEntity_roundTrip() {
        UUID entityId = UUID.randomUUID();
        auditService.record(AuditAction.INCIDENT_CREATED, "INCIDENT", entityId, null, null);

        Page<AuditEntry> page = auditService.findByEntity("INCIDENT", entityId, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1L);
        AuditEntry entry = page.getContent().get(0);
        assertThat(entry.entityType()).isEqualTo("INCIDENT");
        assertThat(entry.entityId()).isEqualTo(entityId);
        assertThat(entry.action()).isEqualTo("INCIDENT_CREATED");
        assertThat(entry.actorUsername()).isEqualTo("integration-test@dora.local");
        assertThat(entry.createdAt()).isNotNull();
        assertThat(entry.tenantId()).isEqualTo(SEEDED_TENANT_ID);
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: findByEntity() returns rows ordered newest first")
    void findByEntity_returnsNewestFirst() throws InterruptedException {
        UUID entityId = UUID.randomUUID();
        auditService.record(AuditAction.INCIDENT_CREATED, "INCIDENT", entityId, null, null);
        Thread.sleep(5);
        auditService.record(AuditAction.INCIDENT_UPDATED, "INCIDENT", entityId, null, null);
        Thread.sleep(5);
        auditService.record(AuditAction.INCIDENT_CLOSED, "INCIDENT", entityId, null, null);

        Page<AuditEntry> page = auditService.findByEntity("INCIDENT", entityId, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(3L);
        assertThat(page.getContent().get(0).action()).isEqualTo("INCIDENT_CLOSED");
        assertThat(page.getContent().get(1).action()).isEqualTo("INCIDENT_UPDATED");
        assertThat(page.getContent().get(2).action()).isEqualTo("INCIDENT_CREATED");
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: pagination — page 0 size 2 of 3 returns 2 entries")
    void findByEntity_pagination_returnsCorrectPage() throws InterruptedException {
        UUID entityId = UUID.randomUUID();
        auditService.record(AuditAction.INCIDENT_CREATED, "INCIDENT", entityId, null, null);
        Thread.sleep(5);
        auditService.record(AuditAction.INCIDENT_UPDATED, "INCIDENT", entityId, null, null);
        Thread.sleep(5);
        auditService.record(AuditAction.INCIDENT_CLOSED, "INCIDENT", entityId, null, null);

        Page<AuditEntry> page = auditService.findByEntity("INCIDENT", entityId, PageRequest.of(0, 2));
        assertThat(page.getTotalElements()).isEqualTo(3L);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.isFirst()).isTrue();
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: context JSONB persisted with three keys: request_id, remote_ip, user_agent")
    void record_persistedContextHasThreeKeys() {
        UUID entityId = UUID.randomUUID();
        auditService.record(AuditAction.INCIDENT_CREATED, "PROBE", entityId, null, null);
        String contextJson = jdbcTemplate.queryForObject(
                "SELECT context::text FROM audit_log WHERE entity_id = ?", String.class, entityId);
        assertThat(contextJson).contains("request_id").contains("remote_ip").contains("user_agent");
    }

    // ── AC-2: trigger tests — use direct JDBC INSERT, independent of JPA ──────

    /**
     * Insert a row via direct SQL (bypasses JPA) to isolate trigger behaviour from entity bugs.
     */
    private UUID insertRowDirectly(String entityType, String action) {
        UUID rowId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO audit_log(id, tenant_id, actor_username, action, entity_type, created_at) " +
                "VALUES (?, ?, 'test', ?, ?, now())",
                rowId, SEEDED_TENANT_ID, action, entityType);
        return rowId;
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2a: Raw UPDATE on audit_log row throws DataAccessException — immutability trigger fires")
    void rawUpdate_auditLogRow_throwsDataAccessException() {
        UUID rowId = insertRowDirectly("PROBE", "SYSTEM");

        assertThatThrownBy(() ->
                jdbcTemplate.update("UPDATE audit_log SET action = 'TAMPERED' WHERE id = ?", rowId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_log rows are immutable");
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2a: After failed UPDATE attempt, row action field remains unchanged")
    void rawUpdate_auditLogRow_rowRemainsUnchanged() {
        UUID rowId = insertRowDirectly("INCIDENT", "INCIDENT_CREATED");

        try {
            jdbcTemplate.update("UPDATE audit_log SET action = 'TAMPERED' WHERE id = ?", rowId);
        } catch (DataAccessException ignored) {}

        String action = jdbcTemplate.queryForObject(
                "SELECT action FROM audit_log WHERE id = ?", String.class, rowId);
        assertThat(action).isEqualTo("INCIDENT_CREATED");
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2b: Raw DELETE on audit_log row throws DataAccessException — immutability trigger fires")
    void rawDelete_auditLogRow_throwsDataAccessException() {
        UUID rowId = insertRowDirectly("PROBE", "SYSTEM");

        assertThatThrownBy(() ->
                jdbcTemplate.update("DELETE FROM audit_log WHERE id = ?", rowId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_log rows are immutable");
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2b: After failed DELETE attempt, row count remains 1 (row not deleted)")
    void rawDelete_auditLogRow_rowCountUnchanged() {
        UUID rowId = insertRowDirectly("PROBE", "SYSTEM");

        try {
            jdbcTemplate.update("DELETE FROM audit_log WHERE id = ?", rowId);
        } catch (DataAccessException ignored) {}

        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE id = ?", Integer.class, rowId);
        assertThat(count).isEqualTo(1);
    }

    // ── AC-3: rollback tests ──────────────────────────────────────────────────

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: audit row is NOT committed when surrounding transaction rolls back")
    void rollback_discardsAuditRow() {
        UUID entityId = UUID.randomUUID();
        try {
            rollbackHelper.recordAndRollback(entityId);
        } catch (RuntimeException expected) {}

        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ?", Integer.class, entityId);
        assertThat(count).as("AC-3: audit row must NOT be present after rollback").isEqualTo(0);
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: audit row IS committed when transaction commits normally")
    void commit_persistsAuditRow() {
        UUID entityId = UUID.randomUUID();
        auditService.record(AuditAction.INCIDENT_CREATED, "INCIDENT", entityId, null, null);

        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ?", Integer.class, entityId);
        assertThat(count).as("AC-3: audit row must be present after commit").isEqualTo(1);
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: multiple records in rolled-back transaction — none committed")
    void rollback_discardsAllAuditRowsInSameTransaction() {
        UUID entityId1 = UUID.randomUUID();
        UUID entityId2 = UUID.randomUUID();
        try {
            rollbackHelper.recordTwoAndRollback(entityId1, entityId2);
        } catch (RuntimeException expected) {}

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ?", Integer.class, entityId1)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ?", Integer.class, entityId2)).isEqualTo(0);
    }
}
