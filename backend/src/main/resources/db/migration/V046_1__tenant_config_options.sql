-- V046_1: Tenant config options and history (was V046a — renamed to valid Flyway format)
CREATE TABLE tenant_config_options (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  category_id UUID NOT NULL REFERENCES config_categories(id),
  code        TEXT NOT NULL,
  label       TEXT NOT NULL,
  metadata    JSONB,
  sort_order  INTEGER NOT NULL DEFAULT 0,
  is_active   BOOLEAN NOT NULL DEFAULT TRUE,
  is_system   BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, category_id, code)
);

CREATE TABLE tenant_config_option_history (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  option_id   UUID NOT NULL REFERENCES tenant_config_options(id),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  changed_by  UUID NOT NULL REFERENCES app_users(id),
  old_label   TEXT,
  new_label   TEXT,
  change_type TEXT NOT NULL CHECK (change_type IN ('create','update','deactivate','reactivate')),
  changed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
  -- Append-only: trigger added in V050
);

CREATE INDEX idx_tenant_config_opts_tenant ON tenant_config_options(tenant_id, category_id);
CREATE INDEX idx_tenant_config_hist_opt    ON tenant_config_option_history(option_id);
