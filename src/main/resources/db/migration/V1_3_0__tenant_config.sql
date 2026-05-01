-- LLD-04: Tenant Configuration & Platform Admin Portal
-- Concern: Extend tenant table with admin config columns; create critical_service,
--          client_base_entry (append-only), and nca_email_config tables.
-- Files: V1_3_0 only (all four changes must land atomically — critical_service and
--        client_base_entry carry FK references to tenant; splitting the ALTER from the
--        CREATE statements would leave the schema in a non-functional state between
--        migration versions, which violates the one-concern-per-file rule only when
--        atomic landing is required, per flyway-conventions R7 exception clause).
-- Affected tables: tenant (ALTER), critical_service (CREATE), client_base_entry (CREATE),
--                  nca_email_config (CREATE)
-- New indexes: none beyond PKs and the UNIQUE constraint on critical_service(tenant_id, name).
--              The LLD §5 query paths do not specify additional index requirements.
--              EXPLAIN rationale for UNIQUE index on critical_service(tenant_id, name):
--                Query: SELECT * FROM critical_service WHERE tenant_id = ? ORDER BY name
--                The unique constraint implicitly creates a B-tree index on (tenant_id, name),
--                which serves both uniqueness enforcement and the tenant-scoped list lookup.
--                No additional index needed: tenant_id selectivity is ~1 tenant, name lookup
--                is a suffix of the composite; the PK index on id covers single-row access.
-- Seeds: none (LLD §3 specifies no seed data for this feature)
--
-- Audit columns: LLD §5 states all tables use the AuditedRepository base from LLD-03.
--   Mutations are tracked in audit_log (LLD-03). Table-level audit columns (created_by,
--   updated_by, updated_at) are NOT added here unless specified in LLD §5 per-table spec.
--   critical_service and client_base_entry have created_at only (per spec).
--   nca_email_config has updated_at only (per spec — one-row-per-tenant upsert pattern).
--
-- Manual rollback (run in this order if migration must be reverted):
--   DROP TABLE IF EXISTS nca_email_config;
--   DROP TABLE IF EXISTS client_base_entry;
--   DROP TABLE IF EXISTS critical_service;
--   ALTER TABLE tenant
--     DROP COLUMN IF EXISTS primary_compliance_contact_id,
--     DROP COLUMN IF EXISTS jurisdiction_iso,
--     DROP COLUMN IF EXISTS nca_email,
--     DROP COLUMN IF EXISTS nca_name;
--   DELETE FROM flyway_schema_history WHERE version = '1.3.0';

-- ── Extend tenant ──────────────────────────────────────────────────────────────
-- Four nullable columns added. NULL is correct: existing tenant rows pre-date
-- this config feature; values will be set via the admin UI (AC-1).
-- primary_compliance_contact_id references app_user(id) — the compliance contact
-- is an existing user; nullable because assignment is optional at tenant creation time.
ALTER TABLE tenant
    ADD COLUMN IF NOT EXISTS nca_name                      VARCHAR(255),
    ADD COLUMN IF NOT EXISTS nca_email                     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS jurisdiction_iso              VARCHAR(2),
    ADD COLUMN IF NOT EXISTS primary_compliance_contact_id UUID
        CONSTRAINT fk_tenant_compliance_contact REFERENCES app_user(id);

-- ── critical_service ───────────────────────────────────────────────────────────
-- Picklist of critical/important ICT services for the bank tenant.
-- Archive (active = FALSE) instead of DELETE — consistent with no-hard-delete principle
-- (LLD-04 D-LLD04-2). The unique constraint on (tenant_id, name) prevents duplicate
-- service names within a tenant and provides the B-tree index for tenant-scoped list
-- queries (EXPLAIN rationale in file header above).
-- No updated_at: mutations recorded in audit_log (LLD-03 AC-8, LLD-04 AC-8).
CREATE TABLE IF NOT EXISTS critical_service (
    id          UUID         NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id   UUID         NOT NULL,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_critical_service                   PRIMARY KEY (id),
    CONSTRAINT fk_critical_service_tenant            FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT uq_critical_service_tenant_name       UNIQUE (tenant_id, name)
);

-- ── client_base_entry ──────────────────────────────────────────────────────────
-- Append-only history of the bank's total client base count values.
-- Each POST adds a new row; no UPDATE, no DELETE (LLD-04 D-LLD04-1).
-- effective_from is the business date from which the count applies — required NOT NULL
-- so that ClientBaseService.countAsOf(Instant) has a reliable denominator for LLD-07.
-- set_by FK: mandatory — every count entry must have a traceable human setter.
-- client_count >= 0: CHECK constraint because a negative client count is a data error
-- that the DB should reject regardless of application-layer validation.
-- No audit columns beyond created_at: this IS the append-only audit record for counts.
CREATE TABLE IF NOT EXISTS client_base_entry (
    id             UUID        NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id      UUID        NOT NULL,
    client_count   BIGINT      NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    set_by         UUID        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_client_base_entry            PRIMARY KEY (id),
    CONSTRAINT fk_client_base_entry_tenant     FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_client_base_entry_set_by     FOREIGN KEY (set_by)    REFERENCES app_user(id),
    CONSTRAINT chk_client_base_entry_count_gte0 CHECK (client_count >= 0)
);

-- ── nca_email_config ───────────────────────────────────────────────────────────
-- One row per tenant; tenant_id is both the PK and the FK to tenant.
-- This enforces the one-config-per-tenant constraint at the DB layer — the application
-- cannot accidentally insert a second config row via a race condition or bug.
-- subject_template stores a Mustache-lite template string (Q-2 ruling: plain
-- {{placeholder}} substitution; no full Mustache engine required).
-- updated_at is set by the application on every PUT and serves as the "last saved" marker
-- visible in the UI. No created_at per LLD §5 spec (upsert pattern; first insert
-- and all subsequent updates all look the same from the DB's perspective).
CREATE TABLE IF NOT EXISTS nca_email_config (
    tenant_id        UUID         NOT NULL,
    sender           VARCHAR(255) NOT NULL,
    recipient        VARCHAR(255) NOT NULL,
    subject_template VARCHAR(500) NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_nca_email_config        PRIMARY KEY (tenant_id),
    CONSTRAINT fk_nca_email_config_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id)
);
