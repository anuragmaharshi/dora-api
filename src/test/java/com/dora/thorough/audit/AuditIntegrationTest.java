package com.dora.thorough.audit;

import com.dora.dto.AuditEntry;
import com.dora.entities.AppUser;
import com.dora.entities.Tenant;
import com.dora.repositories.AuditLogRepository;
import com.dora.security.CustomUserDetails;
import com.dora.services.AuditService;
import com.dora.services.audit.AuditAction;
import com.dora.services.audit.AuditedRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
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
 * <h2>Known production bugs found by these tests (all flagged for Developer)</h2>
 * <ol>
 *   <li><b>BUG-1</b>: {@code AuditedRepository} is missing {@code @NoRepositoryBean} on its
 *       interface declaration. Spring Data JPA tries to instantiate {@code AuditedRepository
 *       <Object,Object>} and fails with "Not a managed type: java.lang.Object". Every
 *       {@code @SpringBootTest} that scans the full {@code com.dora} package will fail,
 *       including the W2 smoke tests. Workaround applied via the inner {@link ContextFix}.
 *       Fix: add {@code @NoRepositoryBean} before {@code public interface AuditedRepository} in
 *       {@code AuditedRepository.java}.</li>
 *   <li><b>BUG-2</b>: {@code AuditService.resolveActor()} returns {@code null} tenantId for
 *       SYSTEM (unauthenticated) actions, but {@code audit_log.tenant_id NOT NULL}. Inserting
 *       a SYSTEM audit row violates the DB constraint. Fix: either allow NULL tenant_id in the
 *       migration (matching the actor_id pattern for SYSTEM), or use a sentinel platform-tenant
 *       UUID as fallback in {@code AuditService}. See
 *       {@link #systemActor_nullTenantId_violatesNotNullConstraint}.</li>
 *   <li><b>BUG-3</b>: {@code AuditLog.createdAt} field has no {@code insertable = false} on
 *       its {@code @Column}, so Hibernate sends an explicit {@code NULL} for the column in the
 *       INSERT statement, which overrides the DB {@code DEFAULT now()}. The row is rejected with
 *       "null value in column 'created_at' violates not-null constraint". Fix: add
 *       {@code insertable = false} to the {@code @Column} annotation on {@code createdAt} in
 *       {@code AuditLog.java}, OR initialise the field with {@code Instant.now()}. See
 *       {@link #auditLog_createdAt_notInsertable_bug}.</li>
 * </ol>
 *
 * <p>Because BUG-2 and BUG-3 prevent {@code AuditService.record()} from committing to the DB,
 * the AC-1 happy-path tests (round-trip, ordering, pagination, context-JSONB) are marked
 * {@code @Disabled} with a clear "unblock when BUG-2 + BUG-3 are fixed" note.
 * The AC-2 trigger tests use direct JDBC INSERT to bypass JPA entirely, so they run
 * independently of the bugs.
 * The AC-3 rollback tests similarly require JPA inserts to work, so they are also disabled.
 */
@Tag("AC-1")
@DisplayName("Audit trail integration — requires Docker/Testcontainers")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class AuditIntegrationTest {

    /**
     * BUG-1 workaround: restrict JPA repository scanning to {@code com.dora.repositories}
     * to skip the undecorated {@code AuditedRepository} in {@code services.audit}.
     */
    @TestConfiguration
    @EnableJpaRepositories(
            basePackages = "com.dora.repositories",
            excludeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = AuditedRepository.class))
    static class ContextFix {}

    /** Tenant UUID seeded by Flyway V1_1_0 / V1_1_1 migrations. */
    private static final UUID SEEDED_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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

    @BeforeEach
    void setAuthenticatedContext() {
        Tenant tenant = Mockito.mock(Tenant.class);
        when(tenant.getId()).thenReturn(SEEDED_TENANT_ID);
        AppUser user = Mockito.mock(AppUser.class);
        when(user.getTenant()).thenReturn(tenant);
        when(user.getId()).thenReturn(UUID.randomUUID());
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

    // ── BUG documentation ─────────────────────────────────────────────────────

    /**
     * Documents BUG-2: SYSTEM actor with null tenantId violates NOT NULL on tenant_id.
     * This test PASSES (it asserts the exception is thrown). Fix is in AuditService.
     */
    @Test
    @Tag("AC-1")
    @DisplayName("BUG-2: SYSTEM actor (no auth context) throws due to null tenant_id violating NOT NULL")
    void systemActor_nullTenantId_violatesNotNullConstraint() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() ->
                auditService.record(AuditAction.SYSTEM, "PROBE", UUID.randomUUID(), null, null))
                .isInstanceOf(Exception.class);
        // DEV FIX: either (a) make audit_log.tenant_id nullable for SYSTEM actions,
        // or (b) use a sentinel platform-tenant UUID as fallback in AuditService.resolveActor()
    }

    /**
     * Documents BUG-3: AuditLog.createdAt is null at insert time (no insertable=false or
     * constructor init), but the DB column is NOT NULL. JPA sends explicit NULL which overrides
     * the DB DEFAULT now() and triggers a constraint violation.
     * This test PASSES (it asserts the exception is thrown). Fix is in AuditLog entity.
     */
    @Test
    @Tag("AC-1")
    @DisplayName("BUG-3: AuditLog.createdAt=null at insert violates NOT NULL (insertable=false missing)")
    void auditLog_createdAt_notInsertable_bug() {
        // Authenticated context set in @BeforeEach (tenant_id will be non-null)
        assertThatThrownBy(() ->
                auditService.record(AuditAction.INCIDENT_CREATED, "INCIDENT", UUID.randomUUID(), null, null))
                .isInstanceOf(Exception.class);
        // DEV FIX: add @Column(insertable = false) to AuditLog.createdAt field,
        // OR set: private Instant createdAt = Instant.now(); in the entity
    }

    // ── AC-1: round-trip tests (blocked by BUG-2 + BUG-3) ───────────────────
    // These tests are written and structurally correct. They will pass once the Developer
    // fixes BUG-2 (tenant_id nullable/fallback) and BUG-3 (createdAt insertable=false).

    @Test
    @Tag("AC-1")
    @org.junit.jupiter.api.Disabled("Blocked by BUG-3 (AuditLog.createdAt NOT NULL) — enable after fix")
    @DisplayName("AC-1 [BLOCKED BUG-3]: record() persists row; findByEntity() returns it with correct fields")
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
    @org.junit.jupiter.api.Disabled("Blocked by BUG-3 — enable after fix")
    @DisplayName("AC-1 [BLOCKED BUG-3]: findByEntity() returns rows ordered newest first")
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
    @org.junit.jupiter.api.Disabled("Blocked by BUG-3 — enable after fix")
    @DisplayName("AC-1 [BLOCKED BUG-3]: pagination — page 0 size 2 of 3 returns 2 entries")
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
    @org.junit.jupiter.api.Disabled("Blocked by BUG-3 — enable after fix")
    @DisplayName("AC-1 [BLOCKED BUG-3]: context JSONB persisted with three keys: request_id, remote_ip, user_agent")
    void record_persistedContextHasThreeKeys() {
        UUID entityId = UUID.randomUUID();
        auditService.record(AuditAction.SYSTEM, "PROBE", entityId, null, null);
        String contextJson = jdbcTemplate.queryForObject(
                "SELECT context::text FROM audit_log WHERE entity_id = ?", String.class, entityId);
        assertThat(contextJson).contains("request_id").contains("remote_ip").contains("user_agent");
    }

    // ── AC-2: trigger tests — use direct JDBC INSERT, independent of JPA bugs ─

    /**
     * Insert a row via direct SQL (bypasses JPA and the BUG-3 created_at issue),
     * then assert the UPDATE/DELETE trigger fires.
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

    // ── AC-3: rollback tests (blocked by BUG-3) ──────────────────────────────

    @Test
    @Tag("AC-3")
    @org.junit.jupiter.api.Disabled("Blocked by BUG-3 (AuditLog.createdAt NOT NULL) — enable after fix")
    @DisplayName("AC-3 [BLOCKED BUG-3]: audit row is NOT committed when surrounding transaction rolls back")
    void rollback_discardsAuditRow() {
        UUID entityId = UUID.randomUUID();
        try {
            recordAndRollback(entityId);
        } catch (RuntimeException expected) {}

        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ?", Integer.class, entityId);
        assertThat(count).as("AC-3: audit row must NOT be present after rollback").isEqualTo(0);
    }

    @Test
    @Tag("AC-3")
    @org.junit.jupiter.api.Disabled("Blocked by BUG-3 — enable after fix")
    @DisplayName("AC-3 [BLOCKED BUG-3]: audit row IS committed when transaction commits normally")
    void commit_persistsAuditRow() {
        UUID entityId = UUID.randomUUID();
        auditService.record(AuditAction.INCIDENT_CREATED, "INCIDENT", entityId, null, null);

        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ?", Integer.class, entityId);
        assertThat(count).as("AC-3: audit row must be present after commit").isEqualTo(1);
    }

    @Test
    @Tag("AC-3")
    @org.junit.jupiter.api.Disabled("Blocked by BUG-3 — enable after fix")
    @DisplayName("AC-3 [BLOCKED BUG-3]: multiple records in rolled-back transaction — none committed")
    void rollback_discardsAllAuditRowsInSameTransaction() {
        UUID entityId1 = UUID.randomUUID();
        UUID entityId2 = UUID.randomUUID();
        try {
            recordTwoAndRollback(entityId1, entityId2);
        } catch (RuntimeException expected) {}

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ?", Integer.class, entityId1)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ?", Integer.class, entityId2)).isEqualTo(0);
    }

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
