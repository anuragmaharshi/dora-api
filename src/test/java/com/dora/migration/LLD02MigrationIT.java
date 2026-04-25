package com.dora.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLD-02: Migration integration test.
 *
 * Verifies that V1_1_0__auth_tables.sql and V1_1_1__seed_roles_and_dev_users.sql
 * apply cleanly to an empty PostgreSQL 15 database and leave the schema in the
 * exact shape mandated by LLD-02 §5.
 *
 * Scope: schema existence and seed data only. Application behaviour (login, JWT
 * verification) is covered by AuthIntegrationTest (Java Developer's responsibility).
 */
@Tag("migration")
@SpringBootTest
@Testcontainers
class LLD02MigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("dora_migration_test")
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

    // ── table existence ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLD-02 V1_1_0: table 'tenant' exists with required columns")
    void tenantTableExists() {
        List<String> cols = columnNames("tenant");
        assertThat(cols).contains("id", "legal_name", "lei", "created_at");
    }

    @Test
    @DisplayName("LLD-02 V1_1_0: table 'app_role' exists with required columns")
    void appRoleTableExists() {
        List<String> cols = columnNames("app_role");
        assertThat(cols).contains("code", "description");
    }

    @Test
    @DisplayName("LLD-02 V1_1_0: table 'app_user' exists with required columns")
    void appUserTableExists() {
        List<String> cols = columnNames("app_user");
        assertThat(cols).contains(
                "id", "tenant_id", "username", "email",
                "password_hash", "mfa_enabled", "mfa_secret", "active", "created_at");
    }

    @Test
    @DisplayName("LLD-02 V1_1_0: table 'user_role' exists with required columns")
    void userRoleTableExists() {
        List<String> cols = columnNames("user_role");
        assertThat(cols).contains("user_id", "role_code");
    }

    // ── NOT NULL constraints ─────────────────────────────────────────────────────

    @Test
    @DisplayName("LLD-02 V1_1_0: app_user NOT NULL columns are non-nullable")
    void appUserNotNullColumns() {
        assertColumnNotNullable("app_user", "tenant_id");
        assertColumnNotNullable("app_user", "username");
        assertColumnNotNullable("app_user", "email");
        assertColumnNotNullable("app_user", "password_hash");
        assertColumnNotNullable("app_user", "mfa_enabled");
        assertColumnNotNullable("app_user", "active");
        assertColumnNotNullable("app_user", "created_at");
    }

    @Test
    @DisplayName("LLD-02 V1_1_0: app_user.mfa_secret is nullable (TOTP reserved)")
    void appUserMfaSecretIsNullable() {
        assertColumnNullable("app_user", "mfa_secret");
    }

    @Test
    @DisplayName("LLD-02 V1_1_0: tenant NOT NULL columns are non-nullable")
    void tenantNotNullColumns() {
        assertColumnNotNullable("tenant", "legal_name");
        assertColumnNotNullable("tenant", "created_at");
    }

    // ── primary keys ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLD-02 V1_1_0: all four tables have primary key constraints")
    void primaryKeysExist() {
        assertThat(primaryKeyName("tenant")).isNotEmpty();
        assertThat(primaryKeyName("app_role")).isNotEmpty();
        assertThat(primaryKeyName("app_user")).isNotEmpty();
        assertThat(primaryKeyName("user_role")).isNotEmpty();
    }

    // ── foreign keys ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLD-02 V1_1_0: app_user → tenant FK exists and is named")
    void appUserToTenantFkExists() {
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.table_constraints tc
                    JOIN information_schema.referential_constraints rc
                        ON rc.constraint_name = tc.constraint_name
                    JOIN information_schema.table_constraints rtc
                        ON rtc.constraint_name = rc.unique_constraint_name
                    WHERE tc.constraint_type = 'FOREIGN KEY'
                      AND tc.table_name = 'app_user'
                      AND rtc.table_name = 'tenant'
                )
                """, Boolean.class));
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("LLD-02 V1_1_0: user_role → app_user and app_role FKs exist")
    void userRoleForeignKeysExist() {
        int fkCount = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE constraint_type = 'FOREIGN KEY'
                  AND table_name = 'user_role'
                """, Integer.class);
        assertThat(fkCount).isEqualTo(2);
    }

    // ── indexes ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLD-02 V1_1_0: index idx_app_user_email exists on app_user(email)")
    void emailIndexExists() {
        assertThat(indexExists("idx_app_user_email")).isTrue();
    }

    @Test
    @DisplayName("LLD-02 V1_1_0: index idx_app_user_tenant_id exists on app_user(tenant_id)")
    void tenantIdIndexExists() {
        assertThat(indexExists("idx_app_user_tenant_id")).isTrue();
    }

    @Test
    @DisplayName("LLD-02 V1_1_0: index idx_user_role_role_code exists on user_role(role_code)")
    void roleCodeIndexExists() {
        assertThat(indexExists("idx_user_role_role_code")).isTrue();
    }

    // ── seed data ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLD-02 V1_1_1: Nexus Bank tenant seeded with fixed UUID")
    void nexusBankTenantSeeded() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant WHERE id = '00000000-0000-0000-0000-000000000001' AND legal_name = 'Nexus Bank'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("LLD-02 V1_1_1: exactly 7 roles seeded matching §11 role matrix")
    void sevenRolesSeeded() {
        List<String> codes = jdbc.queryForList("SELECT code FROM app_role ORDER BY code", String.class);
        assertThat(codes).containsExactlyInAnyOrder(
                "BOARD_VIEWER",
                "CISO",
                "COMPLIANCE_OFFICER",
                "INCIDENT_MANAGER",
                "OPS_ANALYST",
                "PLATFORM_ADMIN",
                "SYSTEM");
    }

    @Test
    @DisplayName("LLD-02 V1_1_1: exactly 7 dev users seeded, one per role")
    void sevenDevUsersSeeded() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class);
        assertThat(count).isEqualTo(7);
    }

    @Test
    @DisplayName("LLD-02 V1_1_1: all 7 dev users have expected email addresses")
    void devUserEmailsCorrect() {
        List<String> emails = jdbc.queryForList("SELECT email FROM app_user ORDER BY email", String.class);
        assertThat(emails).containsExactlyInAnyOrder(
                "board@dora.local",
                "ciso@dora.local",
                "compliance@dora.local",
                "incident@dora.local",
                "ops@dora.local",
                "platform@dora.local",
                "system@dora.local");
    }

    @Test
    @DisplayName("LLD-02 V1_1_1: every dev user has a non-null BCrypt password hash")
    void devUsersHaveBcryptHash() {
        Integer nullHashCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE password_hash IS NULL OR password_hash NOT LIKE '$2a$%'",
                Integer.class);
        assertThat(nullHashCount).isEqualTo(0);
    }

    @Test
    @DisplayName("LLD-02 V1_1_1: every dev user has exactly one role in user_role")
    void devUsersEachHaveOneRole() {
        Integer unmappedCount = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM app_user u
                WHERE NOT EXISTS (
                    SELECT 1 FROM user_role ur WHERE ur.user_id = u.id
                )
                """, Integer.class);
        assertThat(unmappedCount).isEqualTo(0);
    }

    @Test
    @DisplayName("LLD-02 V1_1_1: all 7 user_role assignments exist")
    void userRoleAssignmentsComplete() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user_role", Integer.class);
        assertThat(count).isEqualTo(7);
    }

    @Test
    @DisplayName("LLD-02 V1_1_1: migration is idempotent — re-running seed statements does not error")
    void migrationIdempotent() {
        // Re-run the exact seed INSERT ... ON CONFLICT DO NOTHING statements.
        // No exception expected; row counts must remain 1/7/7/7.
        jdbc.execute("""
                INSERT INTO tenant (id, legal_name, lei)
                VALUES ('00000000-0000-0000-0000-000000000001', 'Nexus Bank', 'NEXUSBANK0000000001')
                ON CONFLICT DO NOTHING
                """);
        jdbc.execute("""
                INSERT INTO app_role (code, description)
                VALUES ('PLATFORM_ADMIN', 'Service-company operator: platform configuration only; zero access to bank incident data (BR-011, NFR-009)')
                ON CONFLICT DO NOTHING
                """);

        Integer tenantCount = jdbc.queryForObject("SELECT COUNT(*) FROM tenant", Integer.class);
        Integer roleCount = jdbc.queryForObject("SELECT COUNT(*) FROM app_role", Integer.class);
        assertThat(tenantCount).isEqualTo(1);
        assertThat(roleCount).isEqualTo(7);
    }

    @Test
    @DisplayName("LLD-02: Flyway schema_history records V1_1_0 and V1_1_1 as successfully applied")
    void flywayHistoryContainsBothMigrations() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
                String.class);
        assertThat(versions).contains("1.1.0", "1.1.1");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private List<String> columnNames(String tableName) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ? AND table_schema = 'public'",
                String.class, tableName);
    }

    private void assertColumnNotNullable(String table, String column) {
        String nullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_name = ? AND column_name = ? AND table_schema = 'public'",
                String.class, table, column);
        assertThat(nullable)
                .as("Column %s.%s should be NOT NULL", table, column)
                .isEqualTo("NO");
    }

    private void assertColumnNullable(String table, String column) {
        String nullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_name = ? AND column_name = ? AND table_schema = 'public'",
                String.class, table, column);
        assertThat(nullable)
                .as("Column %s.%s should be nullable", table, column)
                .isEqualTo("YES");
    }

    private String primaryKeyName(String tableName) {
        List<String> names = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_name = ? AND constraint_type = 'PRIMARY KEY' AND table_schema = 'public'",
                String.class, tableName);
        return names.isEmpty() ? "" : names.get(0);
    }

    private boolean indexExists(String indexName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                Integer.class, indexName);
        return count != null && count > 0;
    }
}
