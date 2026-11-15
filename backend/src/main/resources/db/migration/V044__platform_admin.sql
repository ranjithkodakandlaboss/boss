-- V044: Platform admin users and platform audit events
-- These tables are NOT tenant-scoped and have no RLS.
-- Only boss-admin-api service can write to these tables.

CREATE TABLE platform_admin_users (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  firebase_uid  TEXT NOT NULL UNIQUE,
  email         TEXT NOT NULL UNIQUE,
  display_name  TEXT,
  status        TEXT NOT NULL DEFAULT 'active'
                  CHECK (status IN ('active','suspended','deactivated')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE platform_audit_events (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  admin_id    UUID REFERENCES platform_admin_users(id),
  entity_type TEXT NOT NULL,
  entity_id   UUID,
  action      TEXT NOT NULL,
  old_data    JSONB,
  new_data    JSONB,
  ip_address  INET,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
  -- Append-only: trigger added in V050
);

CREATE INDEX idx_platform_audit_admin    ON platform_audit_events(admin_id);
CREATE INDEX idx_platform_audit_entity   ON platform_audit_events(entity_type, entity_id);
