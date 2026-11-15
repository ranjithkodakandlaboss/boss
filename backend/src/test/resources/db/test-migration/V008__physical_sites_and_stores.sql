-- V008: Physical sites and stores (tenant-scoped)
CREATE TABLE physical_sites (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  legal_entity_id UUID NOT NULL REFERENCES legal_entities(id),
  gst_reg_id      UUID REFERENCES gst_registrations(id),
  name            TEXT NOT NULL,
  code            TEXT NOT NULL,
  site_type       TEXT NOT NULL DEFAULT 'factory'
                    CHECK (site_type IN ('factory','warehouse','office','depot','other')),
  address         JSONB,
  status          TEXT NOT NULL DEFAULT 'active',
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, code)
);

CREATE TABLE stores (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  site_id     UUID NOT NULL REFERENCES physical_sites(id),
  name        TEXT NOT NULL,
  code        TEXT NOT NULL,
  store_type  TEXT NOT NULL DEFAULT 'raw_material'
                CHECK (store_type IN ('raw_material','finished_goods','work_in_progress',
                                     'consumable','tool','rejected','quarantine')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, site_id, code)
);

CREATE INDEX idx_physical_sites_tenant ON physical_sites(tenant_id);
CREATE INDEX idx_stores_tenant_site    ON stores(tenant_id, site_id);
