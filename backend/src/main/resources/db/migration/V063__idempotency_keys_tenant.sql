-- V063: Add tenant_id to idempotency_keys for per-tenant key scoping.
-- The existing key column becomes (tenant_id, key) unique together.

ALTER TABLE idempotency_keys
  ADD COLUMN tenant_id UUID REFERENCES tenant_registry(id);

-- Composite unique: same key may be used by different tenants independently
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_key_key;
ALTER TABLE idempotency_keys
  ADD CONSTRAINT idempotency_keys_tenant_key_uq UNIQUE (tenant_id, key);

-- Drop the old single-column index (was created in V005)
DROP INDEX IF EXISTS idx_idempotency_key;

CREATE INDEX idx_idempotency_tenant_key ON idempotency_keys(tenant_id, key);

-- Enable RLS now that tenant_id exists
ALTER TABLE idempotency_keys ENABLE ROW LEVEL SECURITY;
CREATE POLICY idempotency_keys_isolation ON idempotency_keys
  USING (tenant_id IS NULL OR tenant_id = (
    SELECT current_setting('app.current_tenant_id', true)::uuid));
