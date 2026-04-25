-- LLD-02: Authentication, RBAC & MFA
-- Concern: Seed one tenant, 7 roles (per §11 role matrix), and one dev user per role.
-- Files: V1_1_0 (tables), V1_1_1 (this, seed)
-- Affected tables: tenant, app_role, app_user, user_role
-- New indexes: none
-- Seeds: tenant=Nexus Bank, 7 roles, 7 dev users (one per role)
--
-- Idempotency: all inserts use ON CONFLICT DO NOTHING so re-running on an
-- already-seeded DB is a safe no-op (e.g. after a Flyway repair).
--
-- BCrypt note: password_hash is BCrypt(cost=10) of "ChangeMe!23".
--   Hash: $2a$10$ldsR02RLypaP6LZFpuWcH.89sqbxNNagIG5M3J1NTkJcVM7ZMwgDK
--   Verified via Spring Security BCryptPasswordEncoder.matches() == true.
--   All 7 dev users share the same hash; rotate before any non-local deployment.
--
-- Decision B-1 applied: no refresh_token table — refresh is stateless re-issue.
-- Decision B-4 applied: system@dora.local seeded but used only for unit tests.

-- ── tenant ─────────────────────────────────────────────────────────────────────
INSERT INTO tenant (id, legal_name, lei)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'Nexus Bank',
    'NEXUSBANK0000000001'
)
ON CONFLICT DO NOTHING;

-- ── roles (§11 role matrix) ────────────────────────────────────────────────────
INSERT INTO app_role (code, description) VALUES
    ('PLATFORM_ADMIN',      'Service-company operator: platform configuration only; zero access to bank incident data (BR-011, NFR-009)'),
    ('OPS_ANALYST',         'Bank operations analyst: incident read/write and create/update-status; ops dashboard'),
    ('INCIDENT_MANAGER',    'Bank incident manager: full incident write, classification override, PIR write, ops dashboard'),
    ('COMPLIANCE_OFFICER',  'Bank compliance officer: incident read/write (notes, client-notif status), report submission, compliance dashboard'),
    ('CISO',                'Bank CISO: incident override/sign-off, classification override, PIR sign-off, compliance dashboard'),
    ('BOARD_VIEWER',        'Bank board member: read-only access to major incidents and summary; board dashboard'),
    ('SYSTEM',              'Internal system account: used only for automated/unit-test calls; not assignable to humans')
ON CONFLICT DO NOTHING;

-- ── dev users (one per role) ───────────────────────────────────────────────────
-- username mirrors email local-part for simplicity in dev; they are separate
-- columns so the application can look up by either.

INSERT INTO app_user (id, tenant_id, username, email, password_hash, mfa_enabled, active)
VALUES
    (
        '00000000-0000-0000-0001-000000000001',
        '00000000-0000-0000-0000-000000000001',
        'platform',
        'platform@dora.local',
        '$2a$10$ldsR02RLypaP6LZFpuWcH.89sqbxNNagIG5M3J1NTkJcVM7ZMwgDK',
        FALSE,
        TRUE
    ),
    (
        '00000000-0000-0000-0001-000000000002',
        '00000000-0000-0000-0000-000000000001',
        'ops',
        'ops@dora.local',
        '$2a$10$ldsR02RLypaP6LZFpuWcH.89sqbxNNagIG5M3J1NTkJcVM7ZMwgDK',
        FALSE,
        TRUE
    ),
    (
        '00000000-0000-0000-0001-000000000003',
        '00000000-0000-0000-0000-000000000001',
        'incident',
        'incident@dora.local',
        '$2a$10$ldsR02RLypaP6LZFpuWcH.89sqbxNNagIG5M3J1NTkJcVM7ZMwgDK',
        FALSE,
        TRUE
    ),
    (
        '00000000-0000-0000-0001-000000000004',
        '00000000-0000-0000-0000-000000000001',
        'compliance',
        'compliance@dora.local',
        '$2a$10$ldsR02RLypaP6LZFpuWcH.89sqbxNNagIG5M3J1NTkJcVM7ZMwgDK',
        FALSE,
        TRUE
    ),
    (
        '00000000-0000-0000-0001-000000000005',
        '00000000-0000-0000-0000-000000000001',
        'ciso',
        'ciso@dora.local',
        '$2a$10$ldsR02RLypaP6LZFpuWcH.89sqbxNNagIG5M3J1NTkJcVM7ZMwgDK',
        FALSE,
        TRUE
    ),
    (
        '00000000-0000-0000-0001-000000000006',
        '00000000-0000-0000-0000-000000000001',
        'board',
        'board@dora.local',
        '$2a$10$ldsR02RLypaP6LZFpuWcH.89sqbxNNagIG5M3J1NTkJcVM7ZMwgDK',
        FALSE,
        TRUE
    ),
    (
        '00000000-0000-0000-0001-000000000007',
        '00000000-0000-0000-0000-000000000001',
        'system',
        'system@dora.local',
        '$2a$10$ldsR02RLypaP6LZFpuWcH.89sqbxNNagIG5M3J1NTkJcVM7ZMwgDK',
        FALSE,
        TRUE
    )
ON CONFLICT DO NOTHING;

-- ── user → role assignments ────────────────────────────────────────────────────
INSERT INTO user_role (user_id, role_code) VALUES
    ('00000000-0000-0000-0001-000000000001', 'PLATFORM_ADMIN'),
    ('00000000-0000-0000-0001-000000000002', 'OPS_ANALYST'),
    ('00000000-0000-0000-0001-000000000003', 'INCIDENT_MANAGER'),
    ('00000000-0000-0000-0001-000000000004', 'COMPLIANCE_OFFICER'),
    ('00000000-0000-0000-0001-000000000005', 'CISO'),
    ('00000000-0000-0000-0001-000000000006', 'BOARD_VIEWER'),
    ('00000000-0000-0000-0001-000000000007', 'SYSTEM')
ON CONFLICT DO NOTHING;
