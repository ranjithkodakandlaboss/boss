-- V045: Tenant configuration tables — item categories, asset type configs,
--        inspection parameter templates (FD-57)
CREATE TABLE item_categories (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  code            TEXT NOT NULL,
  name            TEXT NOT NULL,
  parent_id       UUID REFERENCES item_categories(id),
  description     TEXT,
  is_active       BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, code)
);

CREATE TABLE asset_type_configs (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  asset_type      TEXT NOT NULL,
  maintenance_frequency_days INTEGER,
  useful_life_years INTEGER,
  depreciation_method TEXT CHECK (depreciation_method IN ('slm','wdv','none')),
  is_active       BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, asset_type)
);

CREATE TABLE inspection_parameter_templates (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  name            TEXT NOT NULL,
  parameter_name  TEXT NOT NULL,
  spec_type       TEXT NOT NULL CHECK (spec_type IN ('range','boolean','text','measurement')),
  unit            TEXT,
  spec_min        NUMERIC(15,4),
  spec_max        NUMERIC(15,4),
  is_mandatory    BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_item_categories_tenant     ON item_categories(tenant_id);
CREATE INDEX idx_asset_type_configs_tenant  ON asset_type_configs(tenant_id);
CREATE INDEX idx_insp_param_templates       ON inspection_parameter_templates(tenant_id);
