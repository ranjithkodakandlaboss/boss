-- V002: Customer groups and tenant registry (platform-level, no RLS)
CREATE TABLE customer_groups (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name            TEXT NOT NULL,
  slug            TEXT NOT NULL UNIQUE,
  status          TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','suspended','archived')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE tenant_registry (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_group_id UUID NOT NULL REFERENCES customer_groups(id),
  name              TEXT NOT NULL,
  slug              TEXT NOT NULL UNIQUE,
  status            TEXT NOT NULL DEFAULT 'provisioning'
                      CHECK (status IN ('provisioning','active','suspended','archived')),
  plan_tier         TEXT NOT NULL DEFAULT 'pilot',
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenant_registry_status ON tenant_registry(status);
CREATE INDEX idx_tenant_registry_slug   ON tenant_registry(slug);
