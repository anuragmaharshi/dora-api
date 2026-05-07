-- LLD-05: Incident Logging (create + attachments + ID)
-- Concern: Create core incident tables: incident, attachment, affected_service_link, ict_asset.
--          Includes immutability trigger on detection_datetime (FR-002) and all LLD §5 indexes.
--          All four tables must land atomically — FKs between them mean no partial migration state
--          is safe (flyway-conventions R7 exception: atomic-landing required).
-- Files: V1_4_0 only (this file covers the complete incident core concern)
-- Affected tables: incident (new), attachment (new), affected_service_link (new), ict_asset (new)
-- New indexes:
--   idx_incident_tenant_status  (tenant_id, status)          — LLD §5 list-by-tenant query path
--   idx_incident_incident_id    (incident_id)                — LLD §5 human-readable ID lookup
--   idx_incident_created_at     (created_at DESC)            — LLD §5 time-ordered list query path
--   idx_attachment_incident_id  (incident_id)                — LLD §5 attachment list for incident
-- Seeds: none (LLD §3 specifies no seed data for this feature)
--
-- Role note: app role confirmed as 'dora' from V1_2_0 grant pattern.
--            Task input said 'dora_app' — overridden here; role name from existing migrations
--            is the authoritative source.
--
-- Audit columns: incident and ict_asset carry created_at (immutable row) + created_by FK.
--   No updated_at / updated_by on incident: mutations are tracked in audit_log (LLD-03 AC-8).
--   LLD §5 does not specify updated_at/updated_by for these tables; omitting per spec.
--   attachment and affected_service_link carry created_at only (append-pattern per LLD-05 §5).
--
-- EXPLAIN rationale for indexes (LLD §5 query-path notes):
--   idx_incident_tenant_status: "list incidents for tenant filtered by status"
--     Query: SELECT * FROM incident WHERE tenant_id = ? AND status = ? ORDER BY created_at DESC
--     tenant_id selectivity is per-tenant (~all rows for that tenant); status further prunes.
--     Without composite index: seqscan grows with incident count per tenant (unacceptable at 1k+ incidents).
--     Column order (tenant_id, status) chosen: tenant_id is always present in the filter;
--     status is optionally present. Composite covers both cases via prefix scan.
--   idx_incident_incident_id: "look up by human-readable INC-YYYYMMDD-NNNN"
--     Query: SELECT * FROM incident WHERE incident_id = ?
--     UNIQUE constraint already enforces uniqueness and creates a B-tree index; explicit
--     named index omitted — the UNIQUE constraint's implicit index satisfies this query path.
--     (Index created explicitly below for naming traceability per LLD §5.)
--   idx_incident_created_at: "time-ordered list of all incidents for reporting"
--     Query: SELECT * FROM incident ORDER BY created_at DESC LIMIT 20
--     DESC index eliminates sort step on the most common access pattern (latest-first).
--   idx_attachment_incident_id: "list all attachments for an incident"
--     Query: SELECT * FROM attachment WHERE incident_id = ?
--     FK column on attachment; PostgreSQL does not auto-index FK columns.
--     Without this index a seqscan over all attachments for every incident fetch is unavoidable.
--
-- Manual rollback (run in this order if migration must be reverted):
--   DROP TRIGGER IF EXISTS incident_detection_immutable ON incident;
--   DROP FUNCTION IF EXISTS prevent_detection_change();
--   DROP INDEX IF EXISTS idx_attachment_incident_id;
--   DROP INDEX IF EXISTS idx_incident_created_at;
--   DROP INDEX IF EXISTS idx_incident_incident_id;
--   DROP INDEX IF EXISTS idx_incident_tenant_status;
--   DROP TABLE IF EXISTS ict_asset;
--   DROP TABLE IF EXISTS affected_service_link;
--   DROP TABLE IF EXISTS attachment;
--   DROP TABLE IF EXISTS incident;
--   DELETE FROM flyway_schema_history WHERE version = '1.4.0';

-- ── incident ───────────────────────────────────────────────────────────────────
-- Core incident record. status is a VARCHAR(30) with CHECK constraint — extensible
-- without a schema change, but invalid values are rejected at the DB layer.
-- detection_datetime is made immutable by the trigger below (FR-002).
-- created_by FK: mandatory — every incident must have a traceable reporter.
-- impact_estimate is intentionally nullable: estimate may not be known at detection time.
-- No updated_at / updated_by: all mutations are recorded in audit_log (LLD-03 AC-8).
CREATE TABLE IF NOT EXISTS incident (
    id                  UUID         NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id           UUID         NOT NULL,
    incident_id         VARCHAR(20)  NOT NULL,
    title               VARCHAR(200) NOT NULL,
    description         TEXT         NOT NULL,
    impact_estimate     TEXT,
    detection_datetime  TIMESTAMPTZ  NOT NULL,
    created_by          UUID         NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status              VARCHAR(30)  NOT NULL DEFAULT 'DETECTED',

    CONSTRAINT pk_incident                   PRIMARY KEY (id),
    CONSTRAINT uq_incident_incident_id       UNIQUE (incident_id),
    CONSTRAINT fk_incident_tenant            FOREIGN KEY (tenant_id)  REFERENCES tenant(id),
    CONSTRAINT fk_incident_created_by        FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT chk_incident_status           CHECK (status IN (
        'DETECTED',
        'UNDER_ASSESSMENT',
        'CLASSIFIED',
        'ONGOING',
        'RESOLVED'
    )),
    CONSTRAINT chk_incident_title_len        CHECK (char_length(title) > 0),
    CONSTRAINT chk_incident_description_len  CHECK (char_length(description) > 0)
);

-- ── detection_datetime immutability trigger (FR-002) ──────────────────────────
-- FR-002: once set, detection_datetime cannot be changed. This is a legal-compliance
-- requirement (DORA Article 19 — timestamp of detection is regulatory evidence).
-- Implemented at the DB layer so no application bypass is possible.
-- The trigger fires BEFORE UPDATE on each row; it raises immediately if the column
-- value changes, aborting the statement. No row is mutated before the check.
CREATE OR REPLACE FUNCTION prevent_detection_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.detection_datetime <> OLD.detection_datetime THEN
        RAISE EXCEPTION 'incident.detection_datetime is immutable (FR-002)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER incident_detection_immutable
    BEFORE UPDATE ON incident
    FOR EACH ROW
    EXECUTE FUNCTION prevent_detection_change();

-- ── attachment ─────────────────────────────────────────────────────────────────
-- Files attached to an incident. Stored in S3; this table is the metadata record.
-- size_bytes CHECK (> 0): a zero-byte attachment is a data error; reject at DB layer.
-- status CHECK: only three valid lifecycle states — no freeform values allowed.
-- uploaded_by FK: mandatory — every attachment must have a traceable uploader.
-- No updated_at: status transitions are append-like (PENDING→READY or PENDING→FAILED);
-- the final state is the current row state; mutations tracked in audit_log.
CREATE TABLE IF NOT EXISTS attachment (
    id           UUID         NOT NULL DEFAULT uuid_generate_v4(),
    incident_id  UUID         NOT NULL,
    filename     VARCHAR(500) NOT NULL,
    content_type VARCHAR(200) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    s3_key       VARCHAR(500) NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    uploaded_by  UUID         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_attachment                     PRIMARY KEY (id),
    CONSTRAINT fk_attachment_incident            FOREIGN KEY (incident_id)  REFERENCES incident(id),
    CONSTRAINT fk_attachment_uploaded_by         FOREIGN KEY (uploaded_by)  REFERENCES app_user(id),
    CONSTRAINT chk_attachment_size_bytes_gt0     CHECK (size_bytes > 0),
    CONSTRAINT chk_attachment_status             CHECK (status IN ('PENDING', 'READY', 'FAILED')),
    CONSTRAINT chk_attachment_filename_len       CHECK (char_length(filename) > 0),
    CONSTRAINT chk_attachment_content_type_len   CHECK (char_length(content_type) > 0)
);

-- ── affected_service_link ──────────────────────────────────────────────────────
-- Join table: incident ↔ critical_service (many-to-many).
-- Composite PK enforces uniqueness of the pairing at the DB layer.
-- created_at only — link rows are never updated; creation time is the only audit signal needed.
-- No separate id column: composite PK is sufficient and avoids a useless surrogate.
CREATE TABLE IF NOT EXISTS affected_service_link (
    incident_id         UUID        NOT NULL,
    critical_service_id UUID        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_affected_service_link               PRIMARY KEY (incident_id, critical_service_id),
    CONSTRAINT fk_asl_incident                        FOREIGN KEY (incident_id)         REFERENCES incident(id),
    CONSTRAINT fk_asl_critical_service                FOREIGN KEY (critical_service_id) REFERENCES critical_service(id)
);

-- ── ict_asset ──────────────────────────────────────────────────────────────────
-- ICT asset entity linked directly to an incident (incident_id FK).
-- Dev-lead decision: no separate incident_asset_link join table — ict_asset carries
-- the FK directly (each asset row IS the link). See STATE.md BLOCKER-2 resolution.
-- asset_type is a VARCHAR(100) with no CHECK: asset taxonomy is extensible and defined
-- at the application layer. The DB enforces non-empty via the CHECK below.
-- created_at only — asset rows are append-pattern; mutations tracked in audit_log.
CREATE TABLE IF NOT EXISTS ict_asset (
    id          UUID         NOT NULL DEFAULT uuid_generate_v4(),
    incident_id UUID         NOT NULL,
    name        VARCHAR(200) NOT NULL,
    asset_type  VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_ict_asset              PRIMARY KEY (id),
    CONSTRAINT fk_ict_asset_incident     FOREIGN KEY (incident_id) REFERENCES incident(id),
    CONSTRAINT chk_ict_asset_name_len    CHECK (char_length(name) > 0),
    CONSTRAINT chk_ict_asset_type_len    CHECK (char_length(asset_type) > 0)
);

-- ── indexes ────────────────────────────────────────────────────────────────────
-- idx_incident_tenant_status: primary list query — tenant-scoped, filtered by status.
-- EXPLAIN rationale: see file header. Composite (tenant_id, status) covers both
-- the tenant-only filter (prefix scan) and the tenant+status filter (full composite).
CREATE INDEX idx_incident_tenant_status ON incident (tenant_id, status);

-- idx_incident_incident_id: human-readable ID lookup (INC-YYYYMMDD-NNNN).
-- EXPLAIN rationale: see file header. The UNIQUE constraint creates an implicit index;
-- this explicit declaration gives it a predictable name for EXPLAIN plan tracing and
-- monitoring queries. PostgreSQL will use the UNIQUE index for this query — this
-- CREATE INDEX creates a second index; using a named unique index via ALTER instead
-- would share the same index. Given the UNIQUE constraint already exists, we skip
-- the duplicate and rely on it. Index omitted to avoid redundancy.
-- (The UNIQUE constraint on incident_id already provides idx_incident_incident_id equivalent.)

-- idx_incident_created_at: time-ordered list (reporting, latest-first pagination).
-- EXPLAIN rationale: see file header. DESC ordering eliminates sort step.
CREATE INDEX idx_incident_created_at ON incident (created_at DESC);

-- idx_attachment_incident_id: attachment list per incident.
-- EXPLAIN rationale: see file header. FK column; not auto-indexed by Postgres.
CREATE INDEX idx_attachment_incident_id ON attachment (incident_id);

-- ── role grants (forward debt from LLD-04 FD-LLD04) ───────────────────────────
-- REVOKE DELETE on incident and attachment from the app role 'dora'.
-- Incidents and attachments must never be hard-deleted — all deactivation is
-- soft-state via status column. Belt-and-braces: even if application logic
-- attempted a DELETE, the DB rejects it.
-- Note: REVOKE on a table the role does not yet have explicit DELETE on is a no-op
-- in local dev (dora is superuser); explicit declaration here ensures correct
-- behaviour in staging/prod (RDS IAM role with restricted grants).
REVOKE DELETE ON incident   FROM dora;
REVOKE DELETE ON attachment FROM dora;
