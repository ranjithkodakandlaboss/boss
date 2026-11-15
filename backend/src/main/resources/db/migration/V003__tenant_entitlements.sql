-- V003: Tenant feature entitlements
CREATE TABLE tenant_entitlements (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  feature     TEXT NOT NULL,
  max_value   INTEGER,
  enabled     BOOLEAN NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenant_entitlements_tenant ON tenant_entitlements(tenant_id);
