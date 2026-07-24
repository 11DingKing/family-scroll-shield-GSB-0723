CREATE TABLE members (
    id              UUID PRIMARY KEY,
    external_id     VARCHAR(128) NOT NULL UNIQUE,
    display_name    VARCHAR(128) NOT NULL,
    age_group       VARCHAR(16) NOT NULL CHECK (age_group IN ('ADULT','TEEN','CHILD')),
    time_zone       VARCHAR(64) NOT NULL,
    bedtime_local   TIME,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT teen_bedtime_check CHECK (
        age_group <> 'TEEN' OR bedtime_local IS NOT NULL
    )
);

CREATE INDEX idx_members_age_group ON members(age_group);

CREATE TABLE viewing_plans (
    id                  UUID PRIMARY KEY,
    member_id           UUID NOT NULL REFERENCES members(id),
    plan_date           DATE NOT NULL,
    daily_limit_seconds INTEGER NOT NULL,
    consumed_seconds    INTEGER NOT NULL DEFAULT 0,
    extension_used      BOOLEAN NOT NULL DEFAULT FALSE,
    extension_seconds   INTEGER NOT NULL DEFAULT 0,
    status              VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
    closed_at           TIMESTAMPTZ,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT positive_limit CHECK (daily_limit_seconds >= 0),
    CONSTRAINT consumed_non_negative CHECK (consumed_seconds >= 0),
    CONSTRAINT consumed_within_limit CHECK (consumed_seconds <= daily_limit_seconds + extension_seconds),
    UNIQUE (member_id, plan_date)
);

CREATE INDEX idx_viewing_plans_member_date ON viewing_plans(member_id, plan_date);

CREATE TABLE session_leases (
    id                       UUID PRIMARY KEY,
    member_id                UUID NOT NULL REFERENCES members(id),
    plan_id                  UUID NOT NULL REFERENCES viewing_plans(id),
    lease_token              VARCHAR(128) NOT NULL UNIQUE,
    node_id                  VARCHAR(128) NOT NULL,
    status                   VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
                                 CHECK (status IN ('ACTIVE','RELEASED','EXPIRED','REVOKED','CROSSED_MIDNIGHT')),
    granted_at               TIMESTAMPTZ NOT NULL,
    expires_at               TIMESTAMPTZ NOT NULL,
    last_heartbeat_at        TIMESTAMPTZ NOT NULL,
    session_granted_seconds  INTEGER NOT NULL,
    consumed_seconds_total   INTEGER NOT NULL DEFAULT 0,
    last_increment_seconds   INTEGER NOT NULL DEFAULT 0,
    revision                 BIGINT NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT positive_grant CHECK (session_granted_seconds > 0),
    CONSTRAINT expires_after_grant CHECK (expires_at > granted_at),
    CONSTRAINT consumed_non_negative CHECK (consumed_seconds_total >= 0)
);

CREATE UNIQUE INDEX idx_one_active_lease_per_member
    ON session_leases(member_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_leases_member_status ON session_leases(member_id, status);
CREATE INDEX idx_leases_expires_at ON session_leases(expires_at) WHERE status = 'ACTIVE';

CREATE TABLE extension_approvals (
    id                UUID PRIMARY KEY,
    member_id         UUID NOT NULL REFERENCES members(id),
    plan_id           UUID NOT NULL REFERENCES viewing_plans(id),
    lease_id          UUID REFERENCES session_leases(id),
    approver          VARCHAR(128) NOT NULL,
    granted_seconds   INTEGER NOT NULL,
    granted_at        TIMESTAMPTZ NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    reason            VARCHAR(512),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT positive_extension CHECK (granted_seconds > 0),
    CONSTRAINT extension_max_300 CHECK (granted_seconds <= 300),
    CONSTRAINT expires_after_granted CHECK (expires_at > granted_at)
);

CREATE INDEX idx_ext_approvals_member_plan ON extension_approvals(member_id, plan_id);

CREATE TABLE lease_heartbeats (
    id                  BIGSERIAL PRIMARY KEY,
    lease_id            UUID NOT NULL REFERENCES session_leases(id),
    observed_at         TIMESTAMPTZ NOT NULL,
    client_now          TIMESTAMPTZ,
    increment_seconds   INTEGER NOT NULL,
    total_consumed      INTEGER NOT NULL,
    next_expires_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_heartbeats_lease ON lease_heartbeats(lease_id, observed_at);

CREATE TABLE outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(64) NOT NULL,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    payload         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published       BOOLEAN NOT NULL DEFAULT FALSE,
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox_events(published, created_at) WHERE published = FALSE;
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_type, aggregate_id, created_at);
