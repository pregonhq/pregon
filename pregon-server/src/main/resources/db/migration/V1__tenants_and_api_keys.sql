-- Tenants (ADR-0009) and API keys (ADR-0018). Nothing referenced by history is deleted (ADR-0022),
-- so foreign keys restrict deletion explicitly.

CREATE TABLE tenant (
    id         text        PRIMARY KEY CHECK (id ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Single-tenant installs work with zero configuration (ADR-0009).
INSERT INTO tenant (id) VALUES ('default');

CREATE TABLE api_key (
    id           char(8)     PRIMARY KEY,
    tenant_id    text        NOT NULL REFERENCES tenant (id) ON DELETE RESTRICT,
    name         text        NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
    scopes       text[]      NOT NULL CHECK (cardinality(scopes) > 0 AND scopes <@ ARRAY['INGEST', 'ADMIN']),
    -- SHA-256 of the secret; the secret itself is never stored.
    secret_hash  bytea       NOT NULL CHECK (octet_length(secret_hash) = 32),
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    revoked_at   timestamptz
);

CREATE INDEX api_key_tenant_idx ON api_key (tenant_id);
