-- V063: Add tenant_id to idempotency_keys for per-tenant key scoping.
-- Also enables RLS on the table (the V051 block was deferred to here because
-- the tenant_id column did not exist at V051 time).

ALTER TABLE idempotency_keys
  ADD COLUMN tenant_id UUID REFERENCES tenant_registry(id);

-- Store content type alongside the response body for faithful replay
ALTER TABLE idempotency_keys
  ADD COLUMN response_content_type TEXT;

-- Replace the global unique constraint with a composite one
ALTER TABLE idempotency_keys DROP CONSTRAINT IF EXISTS idempotency_keys_key_key;
ALTER TABLE idempotency_keys
  ADD CONSTRAINT idempotency_keys_tenant_key_uq UNIQUE (tenant_id, key);

-- Drop old single-column index (created in V005)
DROP INDEX IF EXISTS idx_idempotency_key;
CREATE INDEX idx_idempotency_tenant_key ON idempotency_keys(tenant_id, key);

-- Enable RLS and create isolation policy
ALTER TABLE idempotency_keys ENABLE ROW LEVEL SECURITY;

-- Drop any stale policy from a previous failed run
DROP POLICY IF EXISTS idempotency_keys_isolation ON idempotency_keys;

-- tenant_id IS NULL = platform-scoped operation (no tenant context required)
CREATE POLICY idempotency_keys_isolation ON idempotency_keys
  USING (tenant_id IS NULL OR tenant_id = boss_current_tenant_id());
