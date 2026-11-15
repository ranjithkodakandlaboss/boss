-- V006: App users, user roles, invite tokens (tenant-scoped)
CREATE TABLE app_users (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  firebase_uid    TEXT NOT NULL,
  email           TEXT NOT NULL,
  display_name    TEXT,
  phone_wa        TEXT,
  status          TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','suspended','deactivated')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, firebase_uid),
  UNIQUE (tenant_id, email)
);

-- V048 correction: PLATFORM_ADMIN is NOT a tenant role (it goes in platform_admin_users)
CREATE TABLE user_roles (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  user_id     UUID NOT NULL REFERENCES app_users(id),
  role        TEXT NOT NULL
                CHECK (role IN (
                  'OWNER','OPS_MANAGER','SALES_MANAGER','PURCHASE_MANAGER',
                  'PRODUCTION_SUPERVISOR','STORE_KEEPER','ACCOUNTS_STAFF',
                  'QUALITY_INSPECTOR','FIELD_WORKER')),
  site_id     UUID,
  granted_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, user_id, role, site_id)
);

CREATE TABLE invite_tokens (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  email       TEXT NOT NULL,
  role        TEXT NOT NULL,
  token_hash  TEXT NOT NULL UNIQUE,
  invited_by  UUID NOT NULL REFERENCES app_users(id),
  status      TEXT NOT NULL DEFAULT 'pending'
                CHECK (status IN ('pending','accepted','expired','cancelled')),
  expires_at  TIMESTAMPTZ NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_app_users_tenant       ON app_users(tenant_id);
CREATE INDEX idx_app_users_firebase_uid ON app_users(tenant_id, firebase_uid);
CREATE INDEX idx_user_roles_tenant_user ON user_roles(tenant_id, user_id);
CREATE INDEX idx_invite_tokens_email    ON invite_tokens(tenant_id, email);
