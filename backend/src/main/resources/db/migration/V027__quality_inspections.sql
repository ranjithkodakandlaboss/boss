-- V027: Quality inspections and inspection parameters (tenant-scoped)
CREATE TABLE quality_inspections (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  inspection_number TEXT NOT NULL,
  inspection_type  TEXT NOT NULL
                     CHECK (inspection_type IN ('incoming','in_process','final','customer')),
  reference_type   TEXT NOT NULL
                     CHECK (reference_type IN ('grn','job_stage','production_job','dispatch')),
  reference_id     UUID NOT NULL,
  item_id          UUID NOT NULL REFERENCES items(id),
  lot_id           UUID REFERENCES material_lots(id),
  quantity_inspected NUMERIC(15,3) NOT NULL,
  quantity_accepted  NUMERIC(15,3),
  quantity_rejected  NUMERIC(15,3),
  result           TEXT CHECK (result IN ('pass','fail','conditional')),
  status           TEXT NOT NULL DEFAULT 'pending'
                     CHECK (status IN ('pending','in_progress','completed','cancelled')),
  inspector_id     UUID,
  inspection_date  DATE,
  notes            TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, inspection_number)
);

CREATE TABLE inspection_parameters (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  inspection_id   UUID NOT NULL REFERENCES quality_inspections(id),
  parameter_name  TEXT NOT NULL,
  spec_min        NUMERIC(15,4),
  spec_max        NUMERIC(15,4),
  spec_target     NUMERIC(15,4),
  actual_value    NUMERIC(15,4),
  result          TEXT CHECK (result IN ('pass','fail')),
  unit            TEXT,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_quality_insp_tenant ON quality_inspections(tenant_id, reference_type, reference_id);
CREATE INDEX idx_insp_params_insp    ON inspection_parameters(tenant_id, inspection_id);
