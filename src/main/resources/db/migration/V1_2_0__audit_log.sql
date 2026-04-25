-- LLD-03: Audit Trail Core
-- Concern: Create append-only audit_log table + immutability trigger + indexes + role grants.
--          This single file bundles table, trigger, indexes, and grants per flyway-conventions
--          R7 audit-log exception: all must land atomically — a table without the trigger
--          would be a temporary security gap that should never exist in any migration state.
-- Files: V1_2_0 only (one concern: the complete audit_log structure)
-- Affected tables: audit_log (new)
-- New indexes:
--   idx_audit_log_entity   (entity_type, entity_id, created_at DESC)  — per LLD-03 §5
--   idx_audit_log_actor    (actor_id, created_at DESC)                 — DECIDE Q-1: YES
-- Seeds: none
--
-- Q-1 ruling (dev-lead surrogate, 2026-04-25): Index (actor_id, created_at DESC) added.
--   EXPLAIN rationale: LLD-15 dashboards will need "everything user X did in last N days".
--   Query pattern: SELECT * FROM audit_log WHERE actor_id = ? ORDER BY created_at DESC LIMIT 20
--   Without this index, a sequential scan grows linearly with total audit row count (~10M+/year).
--   Write penalty is acceptable on an append-only table (no UPDATE, no DELETE hot rows).
--
-- Q-2 ruling (dev-lead surrogate, 2026-04-25): context JSONB stores ONLY:
--   { "request_id": "...", "remote_ip": "...", "user_agent": "..." }
--   No raw HTTP headers. PII-minimisation rationale: remote_ip is forensically necessary;
--   raw headers may expose session cookies, correlation data, or vendor tokens.
--   Enforced at the application layer (AuditService) — documented here for auditability.
--
-- Column name note: LLD-03 §5 specifies columns named "before" and "after". Both are
--   reserved words in PostgreSQL (ISO SQL). Using before_state and after_state instead.
--   The JPA entity (AuditLog.java) maps these via @Column(name="before_state") /
--   @Column(name="after_state"). Java developer must use these exact column names.
--   OPEN-Q raised to java-developer: ensure @Column annotations match before_state/after_state.
--
-- Role grants: App role is 'dora' (spring.datasource.username in application.yml).
--   In the local dev environment the 'dora' role owns the schema, so REVOKE may be a no-op
--   but is declared explicitly for documentation and production parity.
--
-- Retention policy: 7 years (NFR-005). No local purge job. Lifecycle is an RDS concern (LLD-16).
--
-- Manual rollback (run in this order if migration must be reverted):
--   DROP TRIGGER IF EXISTS audit_log_no_mutation ON audit_log;
--   DROP FUNCTION IF EXISTS prevent_audit_mutation();
--   DROP INDEX IF EXISTS idx_audit_log_actor;
--   DROP INDEX IF EXISTS idx_audit_log_entity;
--   DROP TABLE IF EXISTS audit_log;
--   DELETE FROM flyway_schema_history WHERE version = '1.2.0';

-- ── audit_log ──────────────────────────────────────────────────────────────────
-- Append-only audit table. No UPDATE, no DELETE — enforced at the DB layer via
-- trigger below and at the application layer via AuditedRepository (LLD-03 AC-2, AC-6).
-- No audit columns (created_by, updated_by, updated_at) on this table: it IS the audit
-- source of truth. Adding audit columns to an audit table would be circular.
-- created_at is the only time column; it is set once at insert and never changes.
CREATE TABLE audit_log (
    id             UUID          NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id      UUID          NOT NULL,
    actor_id       UUID,                                     -- NULL allowed: SYSTEM actions only
    actor_username VARCHAR(100)  NOT NULL,                   -- denormalised — survives user deactivation
    action         VARCHAR(80)   NOT NULL,                   -- AuditAction enum value
    entity_type    VARCHAR(50)   NOT NULL,                   -- e.g. INCIDENT, CLASSIFICATION
    entity_id      UUID,                                     -- NULL for non-entity events
    before_state   JSONB,                                    -- snapshot before change; NULL on CREATE
    after_state    JSONB,                                    -- snapshot after change; NULL on DELETE
    context        JSONB,                                    -- request_id, remote_ip, user_agent only (Q-2)
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_audit_log             PRIMARY KEY (id),
    CONSTRAINT fk_audit_log_tenant      FOREIGN KEY (tenant_id)  REFERENCES tenant(id),
    CONSTRAINT fk_audit_log_actor       FOREIGN KEY (actor_id)   REFERENCES app_user(id),
    CONSTRAINT chk_audit_log_action_len CHECK (char_length(action) > 0),
    CONSTRAINT chk_audit_log_entity_len CHECK (char_length(entity_type) > 0)
);

-- ── immutability trigger ───────────────────────────────────────────────────────
-- AC-2: any attempt to UPDATE or DELETE an audit_log row — from the application,
-- a direct SQL client, or JPA — is rejected at the database layer.
-- BEFORE trigger: the statement is aborted before any row mutation occurs.
-- Message text is intentionally stable (used in test assertions and log monitoring).
CREATE OR REPLACE FUNCTION prevent_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_log rows are immutable (LLD-03 AC-2)';
END;
$$;

CREATE TRIGGER audit_log_no_mutation
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION prevent_audit_mutation();

-- ── indexes ────────────────────────────────────────────────────────────────────
-- idx_audit_log_entity: primary access pattern — "audit history for entity X"
--   Query: SELECT * FROM audit_log WHERE entity_type = ? AND entity_id = ?
--          ORDER BY created_at DESC
--   Without this index, full scan on growing audit_log (10M+/year) is unacceptable.
--   Partial selectivity on entity_type alone is low (handful of types); the
--   composite on (entity_type, entity_id) provides high selectivity per entity instance.
CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id, created_at DESC);

-- idx_audit_log_actor: secondary access pattern — "everything user X did" (LLD-15 dashboards)
--   Q-1 ruling: YES. Query: SELECT * FROM audit_log WHERE actor_id = ?
--               ORDER BY created_at DESC
--   actor_id has moderate to high selectivity (~1M rows / N users).
--   NULL actor_id rows (SYSTEM actions) are not indexed (NULL values not stored in B-tree
--   by default in Postgres) — SYSTEM rows appear only in full scans, which is acceptable
--   because SYSTEM action queries filter by entity_type, not actor_id.
CREATE INDEX idx_audit_log_actor ON audit_log (actor_id, created_at DESC);

-- ── role grants ────────────────────────────────────────────────────────────────
-- App role 'dora' (spring.datasource.username). INSERT and SELECT only.
-- No UPDATE, no DELETE — the trigger already rejects these, but belt-and-braces:
-- a role without UPDATE/DELETE cannot accidentally escalate past the trigger
-- (e.g. if the trigger were temporarily disabled during maintenance).
-- In local dev 'dora' may be a superuser; the REVOKE is then a no-op but is
-- explicit here so the same migration is correct in staging/prod (RDS IAM role).
REVOKE UPDATE, DELETE ON audit_log FROM dora;
GRANT INSERT, SELECT ON audit_log TO dora;
