-- Family members
CREATE TABLE IF NOT EXISTS family_members (
    id              BIGSERIAL PRIMARY KEY,
    member_uuid     UUID NOT NULL UNIQUE,
    name            VARCHAR(128) NOT NULL,
    role            VARCHAR(16) NOT NULL CHECK (role IN ('ADULT','TEEN','CHILD')),
    timezone        VARCHAR(64) NOT NULL DEFAULT 'UTC',
    bedtime_local   TIME,
    daily_limit_min INTEGER NOT NULL,
    single_limit_min INTEGER NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Daily usage (authoritative quota state) — one row per member per local date
CREATE TABLE IF NOT EXISTS daily_usage (
    id                  BIGSERIAL PRIMARY KEY,
    member_id           BIGINT NOT NULL REFERENCES family_members(id),
    usage_date          DATE NOT NULL,
    daily_limit_min     INTEGER NOT NULL,
    used_minutes        INTEGER NOT NULL DEFAULT 0,
    remaining_minutes   INTEGER NOT NULL,
    extensions_used     INTEGER NOT NULL DEFAULT 0,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (member_id, usage_date)
);

-- Session leases — the unique partial index enforces one active lease per member
CREATE TABLE IF NOT EXISTS session_leases (
    id                  BIGSERIAL PRIMARY KEY,
    lease_token         UUID NOT NULL UNIQUE,
    member_id           BIGINT NOT NULL REFERENCES family_members(id),
    idempotency_key     VARCHAR(128) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RELEASED','EXPIRED','TAKEN_OVER')),
    requested_minutes   INTEGER NOT NULL,
    granted_minutes     INTEGER NOT NULL,
    started_at          TIMESTAMPTZ NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    last_heartbeat_at   TIMESTAMPTZ NOT NULL,
    heartbeat_seq       BIGINT NOT NULL DEFAULT 0,
    last_client_seq     BIGINT NOT NULL DEFAULT 0,
    ended_at            TIMESTAMPTZ,
    consumed_minutes    INTEGER,
    prev_lease_token    UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (member_id, idempotency_key)
);

-- Partial unique index: at most one ACTIVE lease per member at any time
CREATE UNIQUE INDEX IF NOT EXISTS uq_active_lease_per_member
    ON session_leases (member_id)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_leases_heartbeat_seq
    ON session_leases (lease_token, heartbeat_seq);

CREATE INDEX IF NOT EXISTS idx_leases_status_expires
    ON session_leases (status, expires_at);

-- Extension approvals
CREATE TABLE IF NOT EXISTS extension_approvals (
    id                  BIGSERIAL PRIMARY KEY,
    approval_token      UUID NOT NULL UNIQUE,
    member_id           BIGINT NOT NULL REFERENCES family_members(id),
    lease_id            BIGINT REFERENCES session_leases(id),
    idempotency_key     VARCHAR(128) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED','CONSUMED')),
    requested_minutes   INTEGER NOT NULL DEFAULT 5,
    granted_minutes     INTEGER,
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at          TIMESTAMPTZ,
    consumed_at         TIMESTAMPTZ,
    reason              VARCHAR(256),
    UNIQUE (member_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_extensions_member_date
    ON extension_approvals (member_id, requested_at);

-- Transactional outbox
CREATE TABLE IF NOT EXISTS outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID NOT NULL UNIQUE,
    aggregate_type  VARCHAR(64) NOT NULL,
    aggregate_id    VARCHAR(128) NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
    retry_count     INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox_events (created_at) WHERE status = 'PENDING';
