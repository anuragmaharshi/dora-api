-- LLD-04: Tenant Configuration schema for TEST environments.
--
-- This file is a copy of the DB Engineer's migration (feature/LLD-04-admin-migrations,
-- PR #12). It lives here so that @SpringBootTest + Testcontainers can run the full
-- schema without merging PR #12 first. The DB Engineer's migration is authoritative
-- for production and staging; this test copy must be kept in sync until PR #12 merges.
--
-- Once PR #12 merges into main and the main-branch migration is present, this file
-- becomes a no-op via IF NOT EXISTS guards and can be removed.

ALTER TABLE tenant
    ADD COLUMN IF NOT EXISTS nca_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS nca_email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS jurisdiction_iso VARCHAR(2),
    ADD COLUMN IF NOT EXISTS primary_compliance_contact_id UUID REFERENCES app_user(id);

CREATE TABLE IF NOT EXISTS critical_service (
    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_critical_service_tenant_name UNIQUE (tenant_id, name)
);

CREATE TABLE IF NOT EXISTS client_base_entry (
    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    client_count BIGINT NOT NULL CHECK (client_count >= 0),
    effective_from TIMESTAMPTZ NOT NULL,
    set_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS nca_email_config (
    tenant_id UUID NOT NULL PRIMARY KEY REFERENCES tenant(id),
    sender VARCHAR(255) NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    subject_template VARCHAR(500) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
