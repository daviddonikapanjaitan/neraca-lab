-- =====================================================================
-- Neraca Lab - users, roles, permissions and login sessions (PostgreSQL 15+)
--
--   users            accounts with profile (email, address, phone, dob, avatar)
--   roles            named sets of permissions
--   role_permissions permissions of a role: ADMIN, INGESTION, COMPANIES
--   user_roles       roles of a user (one or more)
--   user_sessions    login sessions (SHA-256 of the bearer token, never the token itself)
--
-- The root user (admin) and the built-in Administrator role are created by the backend at
-- startup (AuthBootstrap), so the password is hashed with BCrypt and never reset.
--
-- Executed on every application start by Spring SQL init, after V1.0.7__schema_ingestion.sql.
-- Every statement is idempotent.
-- =====================================================================

-- ---------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    user_id             BIGSERIAL PRIMARY KEY,
    username            VARCHAR(50) NOT NULL,
    email               VARCHAR(255) NOT NULL,
    password_hash       VARCHAR(100) NOT NULL,
    full_name           VARCHAR(150),
    address             VARCHAR(500),
    phone               VARCHAR(30),
    dob                 DATE,
    avatar              BYTEA,
    avatar_content_type VARCHAR(50),
    avatar_updated_at   TIMESTAMPTZ,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    root                BOOLEAN NOT NULL DEFAULT FALSE,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email UNIQUE (email),
    -- both stored lower case, so the unique constraints are case-insensitive
    CONSTRAINT ck_users_username CHECK (username ~ '^[a-z0-9._-]{3,50}$'),
    CONSTRAINT ck_users_email CHECK (email = lower(email) AND email ~ '^[^@[:space:]]+@[^@[:space:]]+$'),
    CONSTRAINT ck_users_avatar CHECK ((avatar IS NULL) = (avatar_content_type IS NULL)),
    CONSTRAINT ck_users_dob CHECK (dob IS NULL OR dob > DATE '1900-01-01')
);

-- at most one root user
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_root ON users (root) WHERE root;

COMMENT ON TABLE  users IS 'Application users. Username and email are unique (stored lower case).';
COMMENT ON COLUMN users.password_hash IS 'BCrypt hash of the password.';
COMMENT ON COLUMN users.avatar IS 'Profile picture (PNG, JPEG, WebP or GIF, at most 2 MB).';
COMMENT ON COLUMN users.root IS 'The root user (admin): cannot be deleted, deactivated or lose the Administrator role.';

-- ---------------------------------------------------------------------
-- roles
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS roles (
    role_id             BIGSERIAL PRIMARY KEY,
    name                VARCHAR(50) NOT NULL,
    description         VARCHAR(255),
    system              BOOLEAN NOT NULL DEFAULT FALSE,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_roles_name CHECK (length(btrim(name)) > 0)
);

-- role names are unique ignoring case
CREATE UNIQUE INDEX IF NOT EXISTS uq_roles_name ON roles (lower(name));
-- at most one built-in role
CREATE UNIQUE INDEX IF NOT EXISTS uq_roles_system ON roles (system) WHERE system;

COMMENT ON TABLE  roles IS 'Named sets of permissions, assigned to users.';
COMMENT ON COLUMN roles.system IS 'The built-in Administrator role: name and permissions cannot be changed, it cannot be deleted.';

-- ---------------------------------------------------------------------
-- role_permissions
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS role_permissions (
    role_id             BIGINT NOT NULL
        REFERENCES roles(role_id) ON DELETE CASCADE,
    permission          VARCHAR(30) NOT NULL,

    CONSTRAINT pk_role_permissions PRIMARY KEY (role_id, permission),
    CONSTRAINT ck_role_permissions_permission CHECK (permission IN ('ADMIN', 'INGESTION', 'COMPANIES'))
);

COMMENT ON TABLE  role_permissions IS 'ADMIN = admin center (users, roles), INGESTION = ingestion page and APIs, COMPANIES = companies pages and APIs.';

-- ---------------------------------------------------------------------
-- user_roles
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_roles (
    user_id             BIGINT NOT NULL
        REFERENCES users(user_id) ON DELETE CASCADE,
    role_id             BIGINT NOT NULL
        REFERENCES roles(role_id) ON DELETE CASCADE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id)
);

CREATE INDEX IF NOT EXISTS ix_user_roles_role ON user_roles (role_id);

-- ---------------------------------------------------------------------
-- user_sessions
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_sessions (
    session_id          BIGSERIAL PRIMARY KEY,
    token_hash          CHAR(64) NOT NULL,
    user_id             BIGINT NOT NULL
        REFERENCES users(user_id) ON DELETE CASCADE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_user_sessions_token UNIQUE (token_hash),
    CONSTRAINT ck_user_sessions_token CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_user_sessions_expiry CHECK (expires_at > created_at)
);

CREATE INDEX IF NOT EXISTS ix_user_sessions_user ON user_sessions (user_id);
CREATE INDEX IF NOT EXISTS ix_user_sessions_expires ON user_sessions (expires_at);

COMMENT ON TABLE  user_sessions IS 'Login sessions. The bearer token is only stored as its SHA-256 hash.';
