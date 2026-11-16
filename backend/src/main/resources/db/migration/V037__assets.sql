-- V037: Assets and asset locations (tenant-scoped)
CREATE TABLE assets (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  asset_number     TEXT NOT NULL,
  name             TEXT NOT NULL,
  asset_type       TEXT NOT NULL,
  site_id          UUID NOT NULL REFERENCES physical_sites(id),
  serial_number    TEXT,
  make             TEXT,
  model            TEXT,
  purchase_date    DATE,
  purchase_cost    NUMERIC(15,2),
  useful_life_years INTEGER,
  status           TEXT NOT NULL DEFAULT 'active'
                     CHECK (status IN ('active','maintenance','disposed','transferred')),
  notes            TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, asset_number)
);

CREATE TABLE asset_locations (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  asset_id        UUID NOT NULL REFERENCES assets(id),
  site_id         UUID NOT NULL REFERENCES physical_sites(id),
  effective_from  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  effective_to    TIMESTAMPTZ,
  notes           TEXT
);

CREATE INDEX idx_assets_tenant         ON assets(tenant_id);
CREATE INDEX idx_asset_locations_asset ON asset_locations(tenant_id, asset_id);
