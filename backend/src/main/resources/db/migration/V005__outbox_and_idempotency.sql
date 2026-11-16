-- V005: Transactional outbox + idempotency keys
CREATE TABLE outbox_events (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID REFERENCES tenant_registry(id),  -- nullable: platform events have no tenant
  aggregate_type  TEXT NOT NULL,
  aggregate_id    UUID NOT NULL,
  event_type      TEXT NOT NULL,
  payload         JSONB NOT NULL,
  status          TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','published','failed')),
  attempts        INTEGER NOT NULL DEFAULT 0,
  published_at    TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- NO RLS: relay job runs as platform service, not as a tenant

CREATE INDEX idx_outbox_pending    ON outbox_events(status, created_at) WHERE status = 'pending';
CREATE INDEX idx_outbox_tenant     ON outbox_events(tenant_id) WHERE tenant_id IS NOT NULL;

CREATE TABLE idempotency_keys (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  key             TEXT NOT NULL UNIQUE,
  request_hash    TEXT NOT NULL,
  response_status INTEGER NOT NULL,
  response_body   JSONB,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_idempotency_key        ON idempotency_keys(key);
CREATE INDEX idx_idempotency_expires_at ON idempotency_keys(expires_at);
