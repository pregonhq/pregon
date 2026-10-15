-- Locations and sources (ADR-0022, ADR-0023). Archived, never deleted.

CREATE TABLE location (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id  text        NOT NULL REFERENCES tenant (id) ON DELETE RESTRICT,
    name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
    timezone   text        NOT NULL,
    -- Value objects always read and written whole.
    shifts     jsonb       NOT NULL,
    status     text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    -- Optimistic concurrency: the ETag of the resource.
    version    bigint      NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id)
);

CREATE INDEX location_page_idx ON location (tenant_id, status, id);

CREATE TABLE source (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       text        NOT NULL,
    location_id     uuid        NOT NULL,
    name            text        NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
    kind            text        NOT NULL,
    role            text        CHECK (role ~ '^[a-z0-9][a-z0-9-]{0,39}$'),
    operating_hours jsonb       NOT NULL,
    interaction     jsonb       NOT NULL,
    audio           jsonb       NOT NULL,
    status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version         bigint      NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    -- A source can only belong to a location of its own tenant, even if application code is wrong.
    FOREIGN KEY (tenant_id, location_id) REFERENCES location (tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX source_page_idx ON source (tenant_id, status, id);
CREATE INDEX source_location_idx ON source (location_id, status);
