-- V004: Audit events — tenant-scoped, append-only (trigger added in V050)
CREATE TABLE audit_events (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  actor_id    UUID,
  entity_type TEXT NOT NULL,
  entity_id   UUID NOT NULL,
  action      TEXT NOT NULL,
  old_data    JSONB,
  new_data    JSONB,
  ip_address  INET,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_events_tenant       ON audit_events(tenant_id);
CREATE INDEX idx_audit_events_entity       ON audit_events(entity_type, entity_id);
CREATE INDEX idx_audit_events_created_at   ON audit_events(created_at DESC);
