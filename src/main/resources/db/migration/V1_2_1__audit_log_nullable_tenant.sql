-- LLD-03 bug-fix: allow NULL tenant_id on audit_log for SYSTEM-scoped events.
--
-- The original V1_2_0 schema set tenant_id NOT NULL, but SYSTEM actions
-- (e.g. scheduled jobs, background workers) do not have an authenticated tenant.
-- Making tenant_id nullable is consistent with actor_id already being nullable
-- for the same reason.  The FK to tenant(id) is retained; NULL simply means
-- the row is not scoped to any tenant.
ALTER TABLE audit_log ALTER COLUMN tenant_id DROP NOT NULL;
