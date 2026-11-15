-- V028: NCRs and concessions (tenant-scoped)
CREATE TABLE ncrs (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  ncr_number       TEXT NOT NULL,
  inspection_id    UUID NOT NULL REFERENCES quality_inspections(id),
  item_id          UUID NOT NULL REFERENCES items(id),
  lot_id           UUID REFERENCES material_lots(id),
  quantity         NUMERIC(15,3) NOT NULL,
  defect_type      TEXT NOT NULL,
  defect_description TEXT,
  root_cause       TEXT,
  status           TEXT NOT NULL DEFAULT 'open'
                     CHECK (status IN ('open','under_review','resolved','closed','scrapped')),
  raised_by        UUID NOT NULL,
  resolved_by      UUID,
  resolution       TEXT,
  resolved_at      TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, ncr_number)
);

CREATE TABLE concessions (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  ncr_id           UUID NOT NULL REFERENCES ncrs(id),
  concession_type  TEXT NOT NULL CHECK (concession_type IN ('use_as_is','rework','conditional')),
  approved_by      UUID,
  approval_notes   TEXT,
  status           TEXT NOT NULL DEFAULT 'pending'
                     CHECK (status IN ('pending','approved','rejected')),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ncrs_tenant     ON ncrs(tenant_id, inspection_id);
CREATE INDEX idx_concessions_ncr ON concessions(tenant_id, ncr_id);
