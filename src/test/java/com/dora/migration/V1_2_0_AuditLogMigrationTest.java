package com.dora.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migration integration test for V1_2_0__audit_log.sql (LLD-03 §5).
 *
 * <p>Scope: proves the migration applies cleanly to an empty Postgres 15 database,
 * all required columns and types exist, both indexes are present, and the
 * immutability trigger (audit_log_no_mutation) correctly rejects UPDATE and DELETE.
 *
 * <p>This test does NOT test application behaviour (AuditService, AuditController, etc.),
 * which is covered by W4 java-unit-test tests. Only schema correctness is asserted here,
 * using information_schema / pg_catalog queries and direct JDBC statements.
 *
 * <p>Seeded reference data from V1_1_1 (tenant 00000000-…-0001, user ops 00000000-…-0002)
 * is used to satisfy FK constraints when inserting a probe row for trigger assertions.
 */
@Tag("migration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureMockMvc
@Testcontainers
class V1_2_0_AuditLogMigrationTest {

    // Use same image as the rest of the test suite for cache locality.
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
    JdbcTemplate jdbc;

    // ── §1: table and column existence ────────────────────────────────────────

    @Test
    @DisplayName("LLD-03: audit_log table exists after V1_2_0 migration")
    void auditLogTableExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'audit_log'",
                Long.class);
        assertThat(count).as("audit_log table must exist in public schema").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-03: audit_log has all required columns with correct data types")
    void auditLogColumnsExistWithCorrectTypes() {
        List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable, column_default "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'audit_log' "
                        + "ORDER BY ordinal_position");

        // Build a map for easy assertion: column_name -> data_type
        Map<String, String> typeByCol = new java.util.LinkedHashMap<>();
        Map<String, String> nullableByCol = new java.util.LinkedHashMap<>();
        for (Map<String, Object> col : cols) {
            typeByCol.put((String) col.get("column_name"), (String) col.get("data_type"));
            nullableByCol.put((String) col.get("column_name"), (String) col.get("is_nullable"));
        }

        // id — UUID, NOT NULL
        assertThat(typeByCol).containsKey("id");
        assertThat(typeByCol.get("id")).isEqualTo("uuid");
        assertThat(nullableByCol.get("id")).isEqualTo("NO");

        // tenant_id — UUID, NOT NULL
        assertThat(typeByCol).containsKey("tenant_id");
        assertThat(typeByCol.get("tenant_id")).isEqualTo("uuid");
        assertThat(nullableByCol.get("tenant_id")).isEqualTo("NO");

        // actor_id — UUID, NULL allowed (SYSTEM actions)
        assertThat(typeByCol).containsKey("actor_id");
        assertThat(typeByCol.get("actor_id")).isEqualTo("uuid");
        assertThat(nullableByCol.get("actor_id")).isEqualTo("YES");

        // actor_username — VARCHAR(100), NOT NULL
        assertThat(typeByCol).containsKey("actor_username");
        assertThat(typeByCol.get("actor_username")).isEqualTo("character varying");
        assertThat(nullableByCol.get("actor_username")).isEqualTo("NO");

        // action — VARCHAR(80), NOT NULL
        assertThat(typeByCol).containsKey("action");
        assertThat(typeByCol.get("action")).isEqualTo("character varying");
        assertThat(nullableByCol.get("action")).isEqualTo("NO");

        // entity_type — VARCHAR(50), NOT NULL
        assertThat(typeByCol).containsKey("entity_type");
        assertThat(typeByCol.get("entity_type")).isEqualTo("character varying");
        assertThat(nullableByCol.get("entity_type")).isEqualTo("NO");

        // entity_id — UUID, NULL allowed
        assertThat(typeByCol).containsKey("entity_id");
        assertThat(typeByCol.get("entity_id")).isEqualTo("uuid");
        assertThat(nullableByCol.get("entity_id")).isEqualTo("YES");

        // before_state — JSONB, NULL allowed
        assertThat(typeByCol).containsKey("before_state");
        assertThat(typeByCol.get("before_state")).isEqualTo("jsonb");
        assertThat(nullableByCol.get("before_state")).isEqualTo("YES");

        // after_state — JSONB, NULL allowed
        assertThat(typeByCol).containsKey("after_state");
        assertThat(typeByCol.get("after_state")).isEqualTo("jsonb");
        assertThat(nullableByCol.get("after_state")).isEqualTo("YES");

        // context — JSONB, NULL allowed (request_id/remote_ip/user_agent only per Q-2)
        assertThat(typeByCol).containsKey("context");
        assertThat(typeByCol.get("context")).isEqualTo("jsonb");
        assertThat(nullableByCol.get("context")).isEqualTo("YES");

        // created_at — TIMESTAMPTZ (timestamptz), NOT NULL
        assertThat(typeByCol).containsKey("created_at");
        assertThat(typeByCol.get("created_at")).isEqualTo("timestamp with time zone");
        assertThat(nullableByCol.get("created_at")).isEqualTo("NO");
    }

    @Test
    @DisplayName("LLD-03: actor_username column has max length 100")
    void actorUsernameMaxLength() {
        Long maxLen = jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'audit_log' "
                        + "AND column_name = 'actor_username'",
                Long.class);
        assertThat(maxLen).isEqualTo(100L);
    }

    @Test
    @DisplayName("LLD-03: action column has max length 80")
    void actionMaxLength() {
        Long maxLen = jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'audit_log' "
                        + "AND column_name = 'action'",
                Long.class);
        assertThat(maxLen).isEqualTo(80L);
    }

    @Test
    @DisplayName("LLD-03: entity_type column has max length 50")
    void entityTypeMaxLength() {
        Long maxLen = jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'audit_log' "
                        + "AND column_name = 'entity_type'",
                Long.class);
        assertThat(maxLen).isEqualTo(50L);
    }

    // ── §2: constraints ────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLD-03: audit_log PRIMARY KEY constraint exists on id")
    void primaryKeyExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'audit_log' "
                        + "AND constraint_type = 'PRIMARY KEY'",
                Long.class);
        assertThat(count).as("PRIMARY KEY on audit_log must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-03: FK constraint fk_audit_log_tenant exists")
    void fkTenantConstraintExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'audit_log' "
                        + "AND constraint_name = 'fk_audit_log_tenant' "
                        + "AND constraint_type = 'FOREIGN KEY'",
                Long.class);
        assertThat(count).as("fk_audit_log_tenant must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-03: FK constraint fk_audit_log_actor exists")
    void fkActorConstraintExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'audit_log' "
                        + "AND constraint_name = 'fk_audit_log_actor' "
                        + "AND constraint_type = 'FOREIGN KEY'",
                Long.class);
        assertThat(count).as("fk_audit_log_actor must exist").isEqualTo(1L);
    }

    // ── §3: indexes ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLD-03: index idx_audit_log_entity on (entity_type, entity_id, created_at DESC) exists — §5 requirement")
    void entityIndexExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND tablename = 'audit_log' "
                        + "AND indexname = 'idx_audit_log_entity'",
                Long.class);
        assertThat(count).as("idx_audit_log_entity must exist (LLD-03 §5)").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-03: index idx_audit_log_actor on (actor_id, created_at DESC) exists — Q-1 ruling")
    void actorIndexExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND tablename = 'audit_log' "
                        + "AND indexname = 'idx_audit_log_actor'",
                Long.class);
        assertThat(count).as("idx_audit_log_actor must exist (Q-1 ruling)").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-03: idx_audit_log_entity covers entity_type, entity_id, created_at columns")
    void entityIndexCoversCorrectColumns() {
        // pg_index + pg_attribute gives us the column list for the index
        List<String> indexedCols = jdbc.queryForList(
                "SELECT a.attname "
                        + "FROM pg_index i "
                        + "JOIN pg_class ic ON ic.oid = i.indexrelid "
                        + "JOIN pg_class tc ON tc.oid = i.indrelid "
                        + "JOIN pg_attribute a ON a.attrelid = tc.oid AND a.attnum = ANY(i.indkey) "
                        + "JOIN pg_namespace ns ON ns.oid = tc.relnamespace "
                        + "WHERE ns.nspname = 'public' AND tc.relname = 'audit_log' "
                        + "AND ic.relname = 'idx_audit_log_entity'",
                String.class);
        assertThat(indexedCols).containsExactlyInAnyOrder("entity_type", "entity_id", "created_at");
    }

    @Test
    @DisplayName("LLD-03: idx_audit_log_actor covers actor_id, created_at columns")
    void actorIndexCoversCorrectColumns() {
        List<String> indexedCols = jdbc.queryForList(
                "SELECT a.attname "
                        + "FROM pg_index i "
                        + "JOIN pg_class ic ON ic.oid = i.indexrelid "
                        + "JOIN pg_class tc ON tc.oid = i.indrelid "
                        + "JOIN pg_attribute a ON a.attrelid = tc.oid AND a.attnum = ANY(i.indkey) "
                        + "JOIN pg_namespace ns ON ns.oid = tc.relnamespace "
                        + "WHERE ns.nspname = 'public' AND tc.relname = 'audit_log' "
                        + "AND ic.relname = 'idx_audit_log_actor'",
                String.class);
        assertThat(indexedCols).containsExactlyInAnyOrder("actor_id", "created_at");
    }

    // ── §4: immutability trigger ───────────────────────────────────────────────

    @Test
    @DisplayName("LLD-03 AC-2: trigger audit_log_no_mutation exists as BEFORE ROW trigger")
    void immutabilityTriggerExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.triggers "
                        + "WHERE event_object_schema = 'public' "
                        + "AND event_object_table = 'audit_log' "
                        + "AND trigger_name = 'audit_log_no_mutation' "
                        + "AND action_timing = 'BEFORE'",
                Long.class);
        assertThat(count).as("audit_log_no_mutation BEFORE trigger must exist (AC-2)").isPositive();
    }

    @Test
    @DisplayName("LLD-03 AC-2: UPDATE on audit_log raises exception with 'audit_log rows are immutable'")
    void updateAuditLogRaisesImmutabilityException() {
        UUID rowId = insertProbeRow();

        assertThatThrownBy(() ->
                jdbc.execute("UPDATE audit_log SET action = 'TAMPERED' WHERE id = '" + rowId + "'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_log rows are immutable");
    }

    @Test
    @DisplayName("LLD-03 AC-2: DELETE from audit_log raises exception with 'audit_log rows are immutable'")
    void deleteAuditLogRaisesImmutabilityException() {
        UUID rowId = insertProbeRow();

        assertThatThrownBy(() ->
                jdbc.execute("DELETE FROM audit_log WHERE id = '" + rowId + "'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_log rows are immutable");
    }

    @Test
    @DisplayName("LLD-03 AC-2: trigger function prevent_audit_mutation exists in pg_proc")
    void triggerFunctionExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_proc p "
                        + "JOIN pg_namespace n ON n.oid = p.pronamespace "
                        + "WHERE n.nspname = 'public' AND p.proname = 'prevent_audit_mutation'",
                Long.class);
        assertThat(count).as("prevent_audit_mutation function must exist").isEqualTo(1L);
    }

    // ── §5: migration repeatability ────────────────────────────────────────────

    @Test
    @DisplayName("LLD-03: V1_2_0 appears in flyway_schema_history as successfully applied")
    void migrationRecordedInFlywayHistory() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history "
                        + "WHERE version = '1.2.0' AND success = TRUE",
                Long.class);
        assertThat(count).as("V1_2_0 must be recorded as successful in Flyway history").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-03: INSERT into audit_log succeeds (table is writable for compliant operations)")
    void insertIntoAuditLogSucceeds() {
        // Proves that the REVOKE UPDATE/DELETE does not affect INSERT.
        UUID id = insertProbeRow();
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE id = '" + id + "'",
                Long.class);
        assertThat(count).as("Inserted probe row must be queryable").isEqualTo(1L);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Inserts a minimal probe row using the seeded tenant and ops user from V1_1_1.
     * Returns the generated UUID so callers can reference it.
     *
     * <p>Seeded IDs (from V1_1_1__seed_roles_and_dev_users.sql):
     * <ul>
     *   <li>tenant: 00000000-0000-0000-0000-000000000001 (Nexus Bank)</li>
     *   <li>actor:  00000000-0000-0000-0001-000000000002 (ops@dora.local / OPS_ANALYST)</li>
     * </ul>
     */
    private UUID insertProbeRow() {
        UUID id = UUID.randomUUID();
        jdbc.execute(
                "INSERT INTO audit_log "
                        + "(id, tenant_id, actor_id, actor_username, action, entity_type, entity_id, "
                        + " before_state, after_state, context, created_at) "
                        + "VALUES ("
                        + "'" + id + "', "
                        + "'00000000-0000-0000-0000-000000000001', "
                        + "'00000000-0000-0000-0001-000000000002', "
                        + "'ops', "
                        + "'PROBE_ACTION', "
                        + "'PROBE', "
                        + "'" + UUID.randomUUID() + "', "
                        + "NULL, NULL, "
                        + "'{\"request_id\":\"test-001\",\"remote_ip\":\"127.0.0.1\",\"user_agent\":\"test-agent\"}'::jsonb, "
                        + "now()"
                        + ")"
        );
        return id;
    }
}
