-- LLD-02: Authentication, RBAC & MFA
-- Concern: Create core auth tables — tenant, app_role, app_user, user_role.
-- Files: V1_1_0 (this), V1_1_1 (seed)
-- Affected tables: tenant, app_role, app_user, user_role
-- New indexes: idx_app_user_email, idx_app_user_tenant_id, idx_user_role_role_code
-- Seeds: none (seed in V1_1_1)
--
-- Notes:
--   - uuid-ossp was enabled in V1_0_0; uuid_generate_v4() is available here.
--   - app_role uses a VARCHAR code as PK (natural key, per LLD-02 §5 role matrix).
--     Role codes are short, stable, and used directly in JWT claims — no surrogate needed.
--   - app_user.username is the login credential; email is separate (unique) for notifications.
--   - mfa_secret is nullable: reserved for TOTP seed; not enforced until LLD-16 (Cognito).
--   - Soft-delete via active=false; no hard DELETE ever (per LLD-02 §5 retention rules).
--   - TIMESTAMPTZ preferred over TIMESTAMP to avoid timezone ambiguity across environments.

-- ── tenant ─────────────────────────────────────────────────────────────────────
CREATE TABLE tenant (
    id          UUID         NOT NULL DEFAULT uuid_generate_v4(),
    legal_name  VARCHAR(255) NOT NULL,
    lei         VARCHAR(20),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_tenant           PRIMARY KEY (id),
    CONSTRAINT uq_tenant_legal_name UNIQUE (legal_name),
    CONSTRAINT uq_tenant_lei        UNIQUE (lei)
);

-- ── app_role ───────────────────────────────────────────────────────────────────
-- Natural PK: role code is the authoritative identifier used in JWT claims and
-- @PreAuthorize expressions.  Surrogate UUID adds no value here.
CREATE TABLE app_role (
    code        VARCHAR(50)  NOT NULL,
    description VARCHAR(255) NOT NULL,

    CONSTRAINT pk_app_role PRIMARY KEY (code)
);

-- ── app_user ───────────────────────────────────────────────────────────────────
CREATE TABLE app_user (
    id            UUID         NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id     UUID         NOT NULL,
    username      VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    mfa_enabled   BOOLEAN      NOT NULL DEFAULT FALSE,
    mfa_secret    VARCHAR(255),                        -- NULL until TOTP enrolled
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_app_user          PRIMARY KEY (id),
    CONSTRAINT uq_app_user_username UNIQUE (username),
    CONSTRAINT uq_app_user_email    UNIQUE (email),
    CONSTRAINT fk_app_user_tenant   FOREIGN KEY (tenant_id) REFERENCES tenant(id)
);

-- ── user_role ──────────────────────────────────────────────────────────────────
CREATE TABLE user_role (
    user_id   UUID        NOT NULL,
    role_code VARCHAR(50) NOT NULL,

    CONSTRAINT pk_user_role        PRIMARY KEY (user_id, role_code),
    CONSTRAINT fk_user_role_user   FOREIGN KEY (user_id)   REFERENCES app_user(id),
    CONSTRAINT fk_user_role_role   FOREIGN KEY (role_code) REFERENCES app_role(code)
);

-- ── indexes ────────────────────────────────────────────────────────────────────
-- idx_app_user_email: login lookup (WHERE email = ?) — unique constraint already
-- creates an index but an explicit name makes it clear in EXPLAIN plans.
-- EXPLAIN rationale: login path executes "SELECT * FROM app_user WHERE email = ?"
-- on every request; without this index a seqscan grows linearly with user count.
CREATE INDEX idx_app_user_email     ON app_user (email);

-- idx_app_user_tenant_id: tenant-scoped user queries (WHERE tenant_id = ?)
-- EXPLAIN rationale: multi-tenant admin screens list users per tenant; FK column
-- is not automatically indexed in Postgres; seqscan unacceptable at >10k users.
CREATE INDEX idx_app_user_tenant_id ON app_user (tenant_id);

-- idx_user_role_role_code: reverse lookup — find all users with a given role.
-- EXPLAIN rationale: role-based dashboards and audit queries filter by role_code;
-- composite PK index covers (user_id, role_code) but not (role_code) alone.
CREATE INDEX idx_user_role_role_code ON user_role (role_code);
