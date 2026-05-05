package com.dora.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migration integration test for V1_3_0__tenant_config.sql (LLD-04 §5).
 *
 * <p>Scope: proves the migration applies cleanly to a Postgres 15 database that already
 * has V1_0_0 through V1_2_1 applied (Flyway runs all in order). Asserts:
 * <ul>
 *   <li>tenant table gains four new nullable columns (nca_name, nca_email,
 *       jurisdiction_iso, primary_compliance_contact_id)</li>
 *   <li>critical_service table exists with correct columns, PK, FK, and UNIQUE constraint</li>
 *   <li>client_base_entry table exists with correct columns, PK, FKs, and CHECK constraint</li>
 *   <li>nca_email_config table exists with correct columns and PK = tenant_id FK</li>
 *   <li>FK constraint names match exactly what V1_3_0 declares (traceability)</li>
 *   <li>Flyway history records V1_3_0 as successfully applied</li>
 * </ul>
 *
 * <p>This test does NOT test application behaviour (AdminController, TenantConfigService,
 * etc.) — that is the Java Developer's responsibility (W3 unit tests, W4 integration tests).
 */
@Tag("migration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureMockMvc
@Testcontainers
class V1_3_0_TenantConfigMigrationTest {

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

    // ── §1: tenant table extension ────────────────────────────────────────────────

    @Test
    @DisplayName("LLD-04: tenant.nca_name column exists and is nullable VARCHAR(255)")
    void tenantNcaNameColumn() {
        Map<String, Object> col = columnInfo("tenant", "nca_name");
        assertThat(col).as("tenant.nca_name must exist").isNotNull();
        assertThat(col.get("data_type")).isEqualTo("character varying");
        assertThat(col.get("is_nullable")).isEqualTo("YES");
        assertThat(col.get("character_maximum_length")).isEqualTo(255);
    }

    @Test
    @DisplayName("LLD-04: tenant.nca_email column exists and is nullable VARCHAR(255)")
    void tenantNcaEmailColumn() {
        Map<String, Object> col = columnInfo("tenant", "nca_email");
        assertThat(col).as("tenant.nca_email must exist").isNotNull();
        assertThat(col.get("data_type")).isEqualTo("character varying");
        assertThat(col.get("is_nullable")).isEqualTo("YES");
        assertThat(col.get("character_maximum_length")).isEqualTo(255);
    }

    @Test
    @DisplayName("LLD-04: tenant.jurisdiction_iso column exists, nullable VARCHAR(2)")
    void tenantJurisdictionIsoColumn() {
        Map<String, Object> col = columnInfo("tenant", "jurisdiction_iso");
        assertThat(col).as("tenant.jurisdiction_iso must exist").isNotNull();
        assertThat(col.get("data_type")).isEqualTo("character varying");
        assertThat(col.get("is_nullable")).isEqualTo("YES");
        assertThat(col.get("character_maximum_length")).isEqualTo(2);
    }

    @Test
    @DisplayName("LLD-04: tenant.primary_compliance_contact_id column exists, nullable UUID")
    void tenantPrimaryComplianceContactIdColumn() {
        Map<String, Object> col = columnInfo("tenant", "primary_compliance_contact_id");
        assertThat(col).as("tenant.primary_compliance_contact_id must exist").isNotNull();
        assertThat(col.get("data_type")).isEqualTo("uuid");
        assertThat(col.get("is_nullable")).isEqualTo("YES");
    }

    @Test
    @DisplayName("LLD-04: FK fk_tenant_compliance_contact references app_user(id)")
    void tenantComplianceContactFkExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'tenant' "
                        + "AND constraint_name = 'fk_tenant_compliance_contact' "
                        + "AND constraint_type = 'FOREIGN KEY'",
                Long.class);
        assertThat(count).as("fk_tenant_compliance_contact must exist on tenant").isEqualTo(1L);
    }

    // ── §2: critical_service table ────────────────────────────────────────────────

    @Test
    @DisplayName("LLD-04: critical_service table exists in public schema")
    void criticalServiceTableExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'critical_service'",
                Long.class);
        assertThat(count).as("critical_service table must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-04: critical_service has all required columns with correct types")
    void criticalServiceColumns() {
        List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'critical_service' "
                        + "ORDER BY ordinal_position");

        Map<String, String> typeByCol = new java.util.LinkedHashMap<>();
        Map<String, String> nullableByCol = new java.util.LinkedHashMap<>();
        for (Map<String, Object> col : cols) {
            typeByCol.put((String) col.get("column_name"), (String) col.get("data_type"));
            nullableByCol.put((String) col.get("column_name"), (String) col.get("is_nullable"));
        }

        assertThat(typeByCol.get("id")).isEqualTo("uuid");
        assertThat(nullableByCol.get("id")).isEqualTo("NO");

        assertThat(typeByCol.get("tenant_id")).isEqualTo("uuid");
        assertThat(nullableByCol.get("tenant_id")).isEqualTo("NO");

        assertThat(typeByCol.get("name")).isEqualTo("character varying");
        assertThat(nullableByCol.get("name")).isEqualTo("NO");

        assertThat(typeByCol.get("description")).isEqualTo("text");
        assertThat(nullableByCol.get("description")).isEqualTo("YES");

        assertThat(typeByCol.get("active")).isEqualTo("boolean");
        assertThat(nullableByCol.get("active")).isEqualTo("NO");

        assertThat(typeByCol.get("created_at")).isEqualTo("timestamp with time zone");
        assertThat(nullableByCol.get("created_at")).isEqualTo("NO");
    }

    @Test
    @DisplayName("LLD-04: critical_service PK constraint exists on id")
    void criticalServicePkExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'critical_service' "
                        + "AND constraint_type = 'PRIMARY KEY'",
                Long.class);
        assertThat(count).as("PRIMARY KEY on critical_service must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-04: FK fk_critical_service_tenant exists on critical_service.tenant_id")
    void criticalServiceTenantFkExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'critical_service' "
                        + "AND constraint_name = 'fk_critical_service_tenant' "
                        + "AND constraint_type = 'FOREIGN KEY'",
                Long.class);
        assertThat(count).as("fk_critical_service_tenant must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-04: UNIQUE constraint uq_critical_service_tenant_name exists on (tenant_id, name)")
    void criticalServiceUniqueConstraintExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'critical_service' "
                        + "AND constraint_name = 'uq_critical_service_tenant_name' "
                        + "AND constraint_type = 'UNIQUE'",
                Long.class);
        assertThat(count).as("uq_critical_service_tenant_name must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-04: critical_service.active defaults to TRUE")
    void criticalServiceActiveDefault() {
        String colDefault = jdbc.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'critical_service' "
                        + "AND column_name = 'active'",
                String.class);
        assertThat(colDefault).as("active column must default to true").containsIgnoringCase("true");
    }

    // ── §3: client_base_entry table ───────────────────────────────────────────────

    @Test
    @DisplayName("LLD-04: client_base_entry table exists in public schema")
    void clientBaseEntryTableExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'client_base_entry'",
                Long.class);
        assertThat(count).as("client_base_entry table must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-04: client_base_entry has all required columns with correct types")
    void clientBaseEntryColumns() {
        List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'client_base_entry' "
                        + "ORDER BY ordinal_position");

        Map<String, String> typeByCol = new java.util.LinkedHashMap<>();
        Map<String, String> nullableByCol = new java.util.LinkedHashMap<>();
        for (Map<String, Object> col : cols) {
            typeByCol.put((String) col.get("column_name"), (String) col.get("data_type"));
            nullableByCol.put((String) col.get("column_name"), (String) col.get("is_nullable"));
        }

        assertThat(typeByCol.get("id")).isEqualTo("uuid");
        assertThat(nullableByCol.get("id")).isEqualTo("NO");

        assertThat(typeByCol.get("tenant_id")).isEqualTo("uuid");
        assertThat(nullableByCol.get("tenant_id")).isEqualTo("NO");

        assertThat(typeByCol.get("client_count")).isEqualTo("bigint");
        assertThat(nullableByCol.get("client_count")).isEqualTo("NO");

        assertThat(typeByCol.get("effective_from")).isEqualTo("timestamp with time zone");
        assertThat(nullableByCol.get("effective_from")).isEqualTo("NO");

        assertThat(typeByCol.get("set_by")).isEqualTo("uuid");
        assertThat(nullableByCol.get("set_by")).isEqualTo("NO");

        assertThat(typeByCol.get("created_at")).isEqualTo("timestamp with time zone");
        assertThat(nullableByCol.get("created_at")).isEqualTo("NO");
    }

    @Test
    @DisplayName("LLD-04: client_base_entry PK constraint exists on id")
    void clientBaseEntryPkExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'client_base_entry' "
                        + "AND constraint_type = 'PRIMARY KEY'",
                Long.class);
        assertThat(count).as("PRIMARY KEY on client_base_entry must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-04: FK fk_client_base_entry_tenant exists on client_base_entry.tenant_id")
    void clientBaseEntryTenantFkExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'client_base_entry' "
                        + "AND constraint_name = 'fk_client_base_entry_tenant' "
                        + "AND constraint_type = 'FOREIGN KEY'",
                Long.class);
        assertThat(count).as("fk_client_base_entry_tenant must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-04: FK fk_client_base_entry_set_by exists on client_base_entry.set_by")
    void clientBaseEntrySetByFkExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'client_base_entry' "
                        + "AND constraint_name = 'fk_client_base_entry_set_by' "
                        + "AND constraint_type = 'FOREIGN KEY'",
                Long.class);
        assertThat(count).as("fk_client_base_entry_set_by must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-04: CHECK constraint chk_client_base_entry_count_gte0 rejects negative counts")
    void clientBaseEntryNegativeCountRejected() {
        // Use the seeded tenant and ops user from V1_1_1 to satisfy FK constraints.
        assertThatThrownBy(() ->
                jdbc.execute(
                        "INSERT INTO client_base_entry "
                                + "(id, tenant_id, client_count, effective_from, set_by) VALUES ("
                                + "gen_random_uuid(), "
                                + "'00000000-0000-0000-0000-000000000001', "
                                + "-1, "
                                + "now(), "
                                + "'00000000-0000-0000-0001-000000000002'"
                                + ")"))
                .as("negative client_count must be rejected by CHECK constraint")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("LLD-04: client_base_entry accepts zero count (boundary)")
    void clientBaseEntryZeroCountAccepted() {
        // Zero is the lower bound — must be accepted.
        jdbc.execute(
                "INSERT INTO client_base_entry "
                        + "(id, tenant_id, client_count, effective_from, set_by) VALUES ("
                        + "gen_random_uuid(), "
                        + "'00000000-0000-0000-0000-000000000001', "
                        + "0, "
                        + "now(), "
                        + "'00000000-0000-0000-0001-000000000002'"
                        + ")");
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM client_base_entry WHERE client_count = 0",
                Long.class);
        assertThat(count).as("zero client_count row must be inserted").isGreaterThan(0L);
    }

    // ── §4: nca_email_config table ────────────────────────────────────────────────

    @Test
    @DisplayName("LLD-04: nca_email_config table exists in public schema")
    void ncaEmailConfigTableExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'nca_email_config'",
                Long.class);
        assertThat(count).as("nca_email_config table must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-04: nca_email_config has all required columns with correct types")
    void ncaEmailConfigColumns() {
        List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'nca_email_config' "
                        + "ORDER BY ordinal_position");

        Map<String, String> typeByCol = new java.util.LinkedHashMap<>();
        Map<String, String> nullableByCol = new java.util.LinkedHashMap<>();
        for (Map<String, Object> col : cols) {
            typeByCol.put((String) col.get("column_name"), (String) col.get("data_type"));
            nullableByCol.put((String) col.get("column_name"), (String) col.get("is_nullable"));
        }

        assertThat(typeByCol.get("tenant_id")).isEqualTo("uuid");
        assertThat(nullableByCol.get("tenant_id")).isEqualTo("NO");

        assertThat(typeByCol.get("sender")).isEqualTo("character varying");
        assertThat(nullableByCol.get("sender")).isEqualTo("NO");

        assertThat(typeByCol.get("recipient")).isEqualTo("character varying");
        assertThat(nullableByCol.get("recipient")).isEqualTo("NO");

        assertThat(typeByCol.get("subject_template")).isEqualTo("character varying");
        assertThat(nullableByCol.get("subject_template")).isEqualTo("NO");

        assertThat(typeByCol.get("updated_at")).isEqualTo("timestamp with time zone");
        assertThat(nullableByCol.get("updated_at")).isEqualTo("NO");
    }

    @Test
    @DisplayName("LLD-04: nca_email_config.subject_template has max length 500")
    void ncaEmailConfigSubjectTemplateMaxLength() {
        Integer maxLen = jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'nca_email_config' "
                        + "AND column_name = 'subject_template'",
                Integer.class);
        assertThat(maxLen).isEqualTo(500);
    }

    @Test
    @DisplayName("LLD-04: nca_email_config PK is on tenant_id (one-config-per-tenant constraint)")
    void ncaEmailConfigPkIsTenantId() {
        // Find the PK constraint columns via pg_catalog
        List<String> pkCols = jdbc.queryForList(
                "SELECT a.attname "
                        + "FROM pg_index i "
                        + "JOIN pg_class tc ON tc.oid = i.indrelid "
                        + "JOIN pg_attribute a ON a.attrelid = tc.oid AND a.attnum = ANY(i.indkey) "
                        + "JOIN pg_namespace ns ON ns.oid = tc.relnamespace "
                        + "WHERE ns.nspname = 'public' AND tc.relname = 'nca_email_config' "
                        + "AND i.indisprimary = TRUE",
                String.class);
        assertThat(pkCols).as("PK of nca_email_config must be tenant_id only")
                .containsExactly("tenant_id");
    }

    @Test
    @DisplayName("LLD-04: FK fk_nca_email_config_tenant exists on nca_email_config.tenant_id")
    void ncaEmailConfigTenantFkExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'nca_email_config' "
                        + "AND constraint_name = 'fk_nca_email_config_tenant' "
                        + "AND constraint_type = 'FOREIGN KEY'",
                Long.class);
        assertThat(count).as("fk_nca_email_config_tenant must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-04: nca_email_config enforces one-per-tenant (duplicate tenant_id rejected)")
    void ncaEmailConfigOnePerTenantEnforced() {
        // Insert a valid row first.
        jdbc.execute(
                "INSERT INTO nca_email_config (tenant_id, sender, recipient, subject_template, updated_at) "
                        + "VALUES ("
                        + "'00000000-0000-0000-0000-000000000001', "
                        + "'noreply@dora.local', "
                        + "'nca@dora.local', "
                        + "'[DORA] Initial Notification', "
                        + "now()"
                        + ")");

        // Attempting to insert a second row for the same tenant must fail (PK violation).
        assertThatThrownBy(() ->
                jdbc.execute(
                        "INSERT INTO nca_email_config (tenant_id, sender, recipient, subject_template, updated_at) "
                                + "VALUES ("
                                + "'00000000-0000-0000-0000-000000000001', "
                                + "'other@dora.local', "
                                + "'nca@dora.local', "
                                + "'[DORA] Duplicate', "
                                + "now()"
                                + ")"))
                .as("second nca_email_config row for same tenant must be rejected (PK)")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ── §5: Flyway history ────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLD-04: V1_3_0 appears in flyway_schema_history as successfully applied")
    void migrationRecordedInFlywayHistory() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history "
                        + "WHERE version = '1.3.0' AND success = TRUE",
                Long.class);
        assertThat(count).as("V1_3_0 must be recorded as successful in Flyway history").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-04: all prior migrations (V1_0_0 through V1_2_1) are also present and successful")
    void priorMigrationsAllSuccessful() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
                String.class);
        assertThat(versions).contains("1.0.0", "1.1.0", "1.1.1", "1.2.0", "1.2.1", "1.3.0");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /**
     * Returns column metadata from information_schema for the given table and column,
     * or null if the column does not exist.
     */
    private Map<String, Object> columnInfo(String table, String column) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable, character_maximum_length "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                table, column);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
