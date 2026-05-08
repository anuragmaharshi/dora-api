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
 * Migration integration test for V1_4_0__incident_core.sql (LLD-05 §5).
 *
 * <p>Scope: proves the migration applies cleanly to a Postgres 15 database that already
 * has V1_0_0 through V1_3_0 applied (Flyway runs all in order). Asserts:
 * <ul>
 *   <li>incident table exists with correct columns, PK, FKs, CHECK constraints, and trigger</li>
 *   <li>attachment table exists with correct columns, PK, FKs, CHECK constraints</li>
 *   <li>affected_service_link join table exists with composite PK and FKs</li>
 *   <li>ict_asset table exists with correct columns, PK, FK</li>
 *   <li>detection_datetime immutability trigger fires on UPDATE (FR-002)</li>
 *   <li>Specified indexes exist (idx_incident_tenant_status, idx_incident_created_at,
 *       idx_attachment_incident_id)</li>
 *   <li>Flyway history records V1_4_0 as successfully applied</li>
 *   <li>Prior migrations V1_0_0 through V1_3_0 are all present and successful</li>
 * </ul>
 *
 * <p>This test does NOT test application behaviour (IncidentController, IncidentService, etc.) —
 * that is the Java Developer's responsibility.
 */
@Tag("migration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureMockMvc
@Testcontainers
class V1_4_0_IncidentCoreMigrationTest {

    // Seeded UUIDs from V1_1_1__seed_roles_and_dev_users.sql
    private static final String TENANT_ID = "'00000000-0000-0000-0000-000000000001'";
    private static final String OPS_USER_ID = "'00000000-0000-0000-0001-000000000002'";

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

    // ── §1: incident table ────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLD-05: incident table exists in public schema")
    void incidentTableExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'incident'",
                Long.class);
        assertThat(count).as("incident table must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-05: incident has all required columns with correct types and nullability")
    void incidentColumns() {
        List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'incident' "
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

        assertThat(typeByCol.get("incident_id")).isEqualTo("character varying");
        assertThat(nullableByCol.get("incident_id")).isEqualTo("NO");

        assertThat(typeByCol.get("title")).isEqualTo("character varying");
        assertThat(nullableByCol.get("title")).isEqualTo("NO");

        assertThat(typeByCol.get("description")).isEqualTo("text");
        assertThat(nullableByCol.get("description")).isEqualTo("NO");

        assertThat(typeByCol.get("impact_estimate")).isEqualTo("text");
        assertThat(nullableByCol.get("impact_estimate")).isEqualTo("YES");  // nullable per spec

        assertThat(typeByCol.get("detection_datetime")).isEqualTo("timestamp with time zone");
        assertThat(nullableByCol.get("detection_datetime")).isEqualTo("NO");

        assertThat(typeByCol.get("created_by")).isEqualTo("uuid");
        assertThat(nullableByCol.get("created_by")).isEqualTo("NO");

        assertThat(typeByCol.get("created_at")).isEqualTo("timestamp with time zone");
        assertThat(nullableByCol.get("created_at")).isEqualTo("NO");

        assertThat(typeByCol.get("status")).isEqualTo("character varying");
        assertThat(nullableByCol.get("status")).isEqualTo("NO");
    }

    @Test
    @DisplayName("LLD-05: incident.incident_id has max length 20")
    void incidentIdMaxLength() {
        Integer maxLen = jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'incident' "
                        + "AND column_name = 'incident_id'",
                Integer.class);
        assertThat(maxLen).as("incident_id must be VARCHAR(20)").isEqualTo(20);
    }

    @Test
    @DisplayName("LLD-05: incident.status defaults to 'DETECTED'")
    void incidentStatusDefault() {
        String colDefault = jdbc.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'incident' "
                        + "AND column_name = 'status'",
                String.class);
        assertThat(colDefault).as("status must default to 'DETECTED'").contains("DETECTED");
    }

    @Test
    @DisplayName("LLD-05: incident PK constraint pk_incident exists")
    void incidentPkExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'incident' "
                        + "AND constraint_type = 'PRIMARY KEY'",
                Long.class);
        assertThat(count).as("PRIMARY KEY on incident must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-05: UNIQUE constraint uq_incident_incident_id exists on incident.incident_id")
    void incidentIdUniqueConstraintExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'incident' "
                        + "AND constraint_name = 'uq_incident_incident_id' "
                        + "AND constraint_type = 'UNIQUE'",
                Long.class);
        assertThat(count).as("uq_incident_incident_id must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-05: FK fk_incident_tenant exists on incident.tenant_id")
    void incidentTenantFkExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'incident' "
                        + "AND constraint_name = 'fk_incident_tenant' "
                        + "AND constraint_type = 'FOREIGN KEY'",
                Long.class);
        assertThat(count).as("fk_incident_tenant must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-05: FK fk_incident_created_by exists on incident.created_by")
    void incidentCreatedByFkExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'incident' "
                        + "AND constraint_name = 'fk_incident_created_by' "
                        + "AND constraint_type = 'FOREIGN KEY'",
                Long.class);
        assertThat(count).as("fk_incident_created_by must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-05: CHECK constraint chk_incident_status rejects invalid status values")
    void incidentStatusCheckRejectsInvalid() {
        assertThatThrownBy(() ->
                jdbc.execute(
                        "INSERT INTO incident (id, tenant_id, incident_id, title, description, "
                                + "detection_datetime, created_by, status) VALUES ("
                                + "gen_random_uuid(), "
                                + TENANT_ID + ", "
                                + "'INC-20260101-9999', "
                                + "'Test Incident', "
                                + "'Description', "
                                + "now(), "
                                + OPS_USER_ID + ", "
                                + "'INVALID_STATUS'"
                                + ")"))
                .as("invalid status must be rejected by CHECK constraint")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("LLD-05: incident accepts all valid status values")
    void incidentStatusCheckAcceptsAllValidValues() {
        String[] validStatuses = {"DETECTED", "UNDER_ASSESSMENT", "CLASSIFIED", "ONGOING", "RESOLVED"};
        int seq = 0;
        for (String status : validStatuses) {
            jdbc.execute(
                    "INSERT INTO incident (id, tenant_id, incident_id, title, description, "
                            + "detection_datetime, created_by, status) VALUES ("
                            + "gen_random_uuid(), "
                            + TENANT_ID + ", "
                            + "'INC-STATUS-" + String.format("%04d", seq++) + "', "
                            + "'Test Incident', "
                            + "'Description', "
                            + "now(), "
                            + OPS_USER_ID + ", "
                            + "'" + status + "'"
                            + ")");
        }
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident WHERE incident_id LIKE 'INC-STATUS-%'",
                Long.class);
        assertThat(count).as("all 5 valid status values must be accepted").isEqualTo(5L);
    }

    // ── §2: detection_datetime immutability trigger (FR-002) ──────────────────────

    @Test
    @DisplayName("LLD-05: trigger incident_detection_immutable exists on incident table")
    void detectionImmutableTriggerExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.triggers "
                        + "WHERE trigger_schema = 'public' "
                        + "AND event_object_table = 'incident' "
                        + "AND trigger_name = 'incident_detection_immutable'",
                Long.class);
        assertThat(count).as("incident_detection_immutable trigger must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-05: updating detection_datetime is rejected by trigger (FR-002)")
    void detectionDatetimeIsImmutable() {
        // Insert an incident.
        jdbc.execute(
                "INSERT INTO incident (id, tenant_id, incident_id, title, description, "
                        + "detection_datetime, created_by) VALUES ("
                        + "'10000000-0000-0000-0000-000000000001', "
                        + TENANT_ID + ", "
                        + "'INC-TRIGGER-0001', "
                        + "'Trigger Test', "
                        + "'Trigger test incident', "
                        + "'2026-01-01T10:00:00Z', "
                        + OPS_USER_ID
                        + ")");

        // Attempting to change detection_datetime must be rejected by the trigger.
        assertThatThrownBy(() ->
                jdbc.execute(
                        "UPDATE incident SET detection_datetime = '2026-01-02T10:00:00Z' "
                                + "WHERE incident_id = 'INC-TRIGGER-0001'"))
                .as("changing detection_datetime must raise exception (FR-002)")
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("LLD-05: updating other incident fields (title) is permitted by trigger")
    void triggerPermitsOtherUpdates() {
        // Insert an incident.
        jdbc.execute(
                "INSERT INTO incident (id, tenant_id, incident_id, title, description, "
                        + "detection_datetime, created_by) VALUES ("
                        + "'10000000-0000-0000-0000-000000000002', "
                        + TENANT_ID + ", "
                        + "'INC-TRIGGER-0002', "
                        + "'Original Title', "
                        + "'Test incident', "
                        + "'2026-01-01T10:00:00Z', "
                        + OPS_USER_ID
                        + ")");

        // Updating title (not detection_datetime) must succeed.
        jdbc.execute(
                "UPDATE incident SET title = 'Updated Title' "
                        + "WHERE incident_id = 'INC-TRIGGER-0002'");

        String title = jdbc.queryForObject(
                "SELECT title FROM incident WHERE incident_id = 'INC-TRIGGER-0002'",
                String.class);
        assertThat(title).isEqualTo("Updated Title");
    }

    // ── §3: attachment table ──────────────────────────────────────────────────────

    @Test
    @DisplayName("LLD-05: attachment table exists in public schema")
    void attachmentTableExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'attachment'",
                Long.class);
        assertThat(count).as("attachment table must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-05: attachment has all required columns with correct types and nullability")
    void attachmentColumns() {
        List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'attachment' "
                        + "ORDER BY ordinal_position");

        Map<String, String> typeByCol = new java.util.LinkedHashMap<>();
        Map<String, String> nullableByCol = new java.util.LinkedHashMap<>();
        for (Map<String, Object> col : cols) {
            typeByCol.put((String) col.get("column_name"), (String) col.get("data_type"));
            nullableByCol.put((String) col.get("column_name"), (String) col.get("is_nullable"));
        }

        assertThat(typeByCol.get("id")).isEqualTo("uuid");
        assertThat(nullableByCol.get("id")).isEqualTo("NO");

        assertThat(typeByCol.get("incident_id")).isEqualTo("uuid");
        assertThat(nullableByCol.get("incident_id")).isEqualTo("NO");

        assertThat(typeByCol.get("filename")).isEqualTo("character varying");
        assertThat(nullableByCol.get("filename")).isEqualTo("NO");

        assertThat(typeByCol.get("content_type")).isEqualTo("character varying");
        assertThat(nullableByCol.get("content_type")).isEqualTo("NO");

        assertThat(typeByCol.get("size_bytes")).isEqualTo("bigint");
        assertThat(nullableByCol.get("size_bytes")).isEqualTo("NO");

        assertThat(typeByCol.get("s3_key")).isEqualTo("character varying");
        assertThat(nullableByCol.get("s3_key")).isEqualTo("NO");

        assertThat(typeByCol.get("status")).isEqualTo("character varying");
        assertThat(nullableByCol.get("status")).isEqualTo("NO");

        assertThat(typeByCol.get("uploaded_by")).isEqualTo("uuid");
        assertThat(nullableByCol.get("uploaded_by")).isEqualTo("NO");

        assertThat(typeByCol.get("created_at")).isEqualTo("timestamp with time zone");
        assertThat(nullableByCol.get("created_at")).isEqualTo("NO");
    }

    @Test
    @DisplayName("LLD-05: FK fk_attachment_incident exists on attachment.incident_id")
    void attachmentIncidentFkExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'attachment' "
                        + "AND constraint_name = 'fk_attachment_incident' "
                        + "AND constraint_type = 'FOREIGN KEY'",
                Long.class);
        assertThat(count).as("fk_attachment_incident must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-05: FK fk_attachment_uploaded_by exists on attachment.uploaded_by")
    void attachmentUploadedByFkExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'attachment' "
                        + "AND constraint_name = 'fk_attachment_uploaded_by' "
                        + "AND constraint_type = 'FOREIGN KEY'",
                Long.class);
        assertThat(count).as("fk_attachment_uploaded_by must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-05: CHECK chk_attachment_size_bytes_gt0 rejects zero and negative size_bytes")
    void attachmentSizeBytesCheckRejectsZeroAndNegative() {
        // First create an incident to FK against.
        jdbc.execute(
                "INSERT INTO incident (id, tenant_id, incident_id, title, description, "
                        + "detection_datetime, created_by) VALUES ("
                        + "'20000000-0000-0000-0000-000000000001', "
                        + TENANT_ID + ", "
                        + "'INC-ATTACH-0001', "
                        + "'Attachment Test', "
                        + "'Test', "
                        + "now(), "
                        + OPS_USER_ID
                        + ")");

        assertThatThrownBy(() ->
                jdbc.execute(
                        "INSERT INTO attachment (id, incident_id, filename, content_type, "
                                + "size_bytes, s3_key, uploaded_by) VALUES ("
                                + "gen_random_uuid(), "
                                + "'20000000-0000-0000-0000-000000000001', "
                                + "'test.pdf', "
                                + "'application/pdf', "
                                + "0, "
                                + "'bucket/test.pdf', "
                                + OPS_USER_ID
                                + ")"))
                .as("zero size_bytes must be rejected by CHECK constraint")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("LLD-05: CHECK chk_attachment_status rejects invalid attachment status values")
    void attachmentStatusCheckRejectsInvalid() {
        jdbc.execute(
                "INSERT INTO incident (id, tenant_id, incident_id, title, description, "
                        + "detection_datetime, created_by) VALUES ("
                        + "'20000000-0000-0000-0000-000000000002', "
                        + TENANT_ID + ", "
                        + "'INC-ATTACH-0002', "
                        + "'Attachment Status Test', "
                        + "'Test', "
                        + "now(), "
                        + OPS_USER_ID
                        + ")");

        assertThatThrownBy(() ->
                jdbc.execute(
                        "INSERT INTO attachment (id, incident_id, filename, content_type, "
                                + "size_bytes, s3_key, status, uploaded_by) VALUES ("
                                + "gen_random_uuid(), "
                                + "'20000000-0000-0000-0000-000000000002', "
                                + "'test.pdf', "
                                + "'application/pdf', "
                                + "1024, "
                                + "'bucket/test.pdf', "
                                + "'UPLOADED', "
                                + OPS_USER_ID
                                + ")"))
                .as("invalid attachment status must be rejected by CHECK constraint")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("LLD-05: attachment.status defaults to 'PENDING'")
    void attachmentStatusDefault() {
        String colDefault = jdbc.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'attachment' "
                        + "AND column_name = 'status'",
                String.class);
        assertThat(colDefault).as("attachment.status must default to 'PENDING'").contains("PENDING");
    }

    // ── §4: affected_service_link table ───────────────────────────────────────────

    @Test
    @DisplayName("LLD-05: affected_service_link table exists in public schema")
    void affectedServiceLinkTableExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'affected_service_link'",
                Long.class);
        assertThat(count).as("affected_service_link table must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-05: affected_service_link has composite PK on (incident_id, critical_service_id)")
    void affectedServiceLinkCompositePkExists() {
        List<String> pkCols = jdbc.queryForList(
                "SELECT a.attname "
                        + "FROM pg_index i "
                        + "JOIN pg_class tc ON tc.oid = i.indrelid "
                        + "JOIN pg_attribute a ON a.attrelid = tc.oid AND a.attnum = ANY(i.indkey) "
                        + "JOIN pg_namespace ns ON ns.oid = tc.relnamespace "
                        + "WHERE ns.nspname = 'public' AND tc.relname = 'affected_service_link' "
                        + "AND i.indisprimary = TRUE "
                        + "ORDER BY a.attname",
                String.class);
        assertThat(pkCols).as("PK of affected_service_link must be (critical_service_id, incident_id)")
                .containsExactlyInAnyOrder("incident_id", "critical_service_id");
    }

    @Test
    @DisplayName("LLD-05: FK fk_asl_incident exists on affected_service_link.incident_id")
    void affectedServiceLinkIncidentFkExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'affected_service_link' "
                        + "AND constraint_name = 'fk_asl_incident' "
                        + "AND constraint_type = 'FOREIGN KEY'",
                Long.class);
        assertThat(count).as("fk_asl_incident must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-05: FK fk_asl_critical_service exists on affected_service_link.critical_service_id")
    void affectedServiceLinkCriticalServiceFkExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'affected_service_link' "
                        + "AND constraint_name = 'fk_asl_critical_service' "
                        + "AND constraint_type = 'FOREIGN KEY'",
                Long.class);
        assertThat(count).as("fk_asl_critical_service must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-05: affected_service_link rejects duplicate (incident_id, critical_service_id) pairs")
    void affectedServiceLinkDuplicateRejected() {
        // Create incident and critical_service.
        jdbc.execute(
                "INSERT INTO incident (id, tenant_id, incident_id, title, description, "
                        + "detection_datetime, created_by) VALUES ("
                        + "'30000000-0000-0000-0000-000000000001', "
                        + TENANT_ID + ", "
                        + "'INC-ASL-0001', "
                        + "'ASL Test', "
                        + "'Test', "
                        + "now(), "
                        + OPS_USER_ID
                        + ")");
        jdbc.execute(
                "INSERT INTO critical_service (id, tenant_id, name) VALUES ("
                        + "'30000000-0000-0000-0001-000000000001', "
                        + TENANT_ID + ", "
                        + "'Payment Gateway'"
                        + ")");

        // Insert link once — should succeed.
        jdbc.execute(
                "INSERT INTO affected_service_link (incident_id, critical_service_id) VALUES ("
                        + "'30000000-0000-0000-0000-000000000001', "
                        + "'30000000-0000-0000-0001-000000000001'"
                        + ")");

        // Insert same link again — must be rejected by composite PK.
        assertThatThrownBy(() ->
                jdbc.execute(
                        "INSERT INTO affected_service_link (incident_id, critical_service_id) VALUES ("
                                + "'30000000-0000-0000-0000-000000000001', "
                                + "'30000000-0000-0000-0001-000000000001'"
                                + ")"))
                .as("duplicate (incident_id, critical_service_id) must be rejected by composite PK")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ── §5: ict_asset table ───────────────────────────────────────────────────────

    @Test
    @DisplayName("LLD-05: ict_asset table exists in public schema")
    void ictAssetTableExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'ict_asset'",
                Long.class);
        assertThat(count).as("ict_asset table must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-05: ict_asset has all required columns with correct types and nullability")
    void ictAssetColumns() {
        List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'ict_asset' "
                        + "ORDER BY ordinal_position");

        Map<String, String> typeByCol = new java.util.LinkedHashMap<>();
        Map<String, String> nullableByCol = new java.util.LinkedHashMap<>();
        for (Map<String, Object> col : cols) {
            typeByCol.put((String) col.get("column_name"), (String) col.get("data_type"));
            nullableByCol.put((String) col.get("column_name"), (String) col.get("is_nullable"));
        }

        assertThat(typeByCol.get("id")).isEqualTo("uuid");
        assertThat(nullableByCol.get("id")).isEqualTo("NO");

        assertThat(typeByCol.get("incident_id")).isEqualTo("uuid");
        assertThat(nullableByCol.get("incident_id")).isEqualTo("NO");

        assertThat(typeByCol.get("name")).isEqualTo("character varying");
        assertThat(nullableByCol.get("name")).isEqualTo("NO");

        assertThat(typeByCol.get("asset_type")).isEqualTo("character varying");
        assertThat(nullableByCol.get("asset_type")).isEqualTo("NO");

        assertThat(typeByCol.get("created_at")).isEqualTo("timestamp with time zone");
        assertThat(nullableByCol.get("created_at")).isEqualTo("NO");
    }

    @Test
    @DisplayName("LLD-05: FK fk_ict_asset_incident exists on ict_asset.incident_id")
    void ictAssetIncidentFkExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = 'ict_asset' "
                        + "AND constraint_name = 'fk_ict_asset_incident' "
                        + "AND constraint_type = 'FOREIGN KEY'",
                Long.class);
        assertThat(count).as("fk_ict_asset_incident must exist").isEqualTo(1L);
    }

    // ── §6: indexes ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLD-05: idx_incident_tenant_status exists on incident(tenant_id, status)")
    void indexIncidentTenantStatusExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND tablename = 'incident' "
                        + "AND indexname = 'idx_incident_tenant_status'",
                Long.class);
        assertThat(count).as("idx_incident_tenant_status must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-05: idx_incident_created_at exists on incident(created_at DESC)")
    void indexIncidentCreatedAtExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND tablename = 'incident' "
                        + "AND indexname = 'idx_incident_created_at'",
                Long.class);
        assertThat(count).as("idx_incident_created_at must exist").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-05: idx_attachment_incident_id exists on attachment(incident_id)")
    void indexAttachmentIncidentIdExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND tablename = 'attachment' "
                        + "AND indexname = 'idx_attachment_incident_id'",
                Long.class);
        assertThat(count).as("idx_attachment_incident_id must exist").isEqualTo(1L);
    }

    // ── §7: Flyway history ────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLD-05: V1_4_0 appears in flyway_schema_history as successfully applied")
    void migrationRecordedInFlywayHistory() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history "
                        + "WHERE version = '1.4.0' AND success = TRUE",
                Long.class);
        assertThat(count).as("V1_4_0 must be recorded as successful in Flyway history").isEqualTo(1L);
    }

    @Test
    @DisplayName("LLD-05: all prior migrations V1_0_0 through V1_3_0 are present and successful")
    void priorMigrationsAllSuccessful() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
                String.class);
        assertThat(versions).contains("1.0.0", "1.1.0", "1.1.1", "1.2.0", "1.2.1", "1.3.0", "1.4.0");
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
