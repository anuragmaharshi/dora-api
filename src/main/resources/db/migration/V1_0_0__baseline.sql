-- LLD-01: Local Dev Baseline & Repo Scaffolding
-- DORA baseline. Business tables begin at LLD-03.
--
-- Concern: Register uuid-ossp so uuid_generate_v4() is available to all subsequent migrations.
-- Files: V1_0_0 only (this LLD adds no tables).
-- Affected tables: none
-- New indexes: none
-- Seeds: none
--
-- Idempotency: CREATE EXTENSION IF NOT EXISTS is a no-op on re-run.
-- Flyway baseline-version=0 means this V1_0_0 is the first real migration;
-- running it twice on the same DB will not error.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
