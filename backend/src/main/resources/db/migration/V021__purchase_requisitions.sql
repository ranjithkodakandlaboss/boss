-- V021: Purchase Requisitions and PR lines (tenant-scoped)
CREATE TABLE purchase_requisitions (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  pr_number        TEXT NOT NULL,
  site_id          UUID NOT NULL REFERENCES physical_sites(id),
  status           TEXT NOT NULL DEFAULT 'draft'
                     CHECK (status IN ('draft','submitted','approved','rejected','cancelled','fulfilled')),
  priority         TEXT NOT NULL DEFAULT 'normal'
                     CHECK (priority IN ('low','normal','high','urgent')),
  required_by_date DATE,
  reference_type   TEXT CHECK (reference_type IN ('production_job','manual','reorder')),
  reference_id     UUID,
  notes            TEXT,
  requested_by     UUID NOT NULL,
  approved_by      UUID,
  approved_at      TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, pr_number)
);

CREATE TABLE pr_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  pr_id           UUID NOT NULL REFERENCES purchase_requisitions(id),
  line_no         INTEGER NOT NULL,
  item_id         UUID NOT NULL REFERENCES items(id),
  quantity        NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  est_unit_price  NUMERIC(15,2),
  store_id        UUID REFERENCES stores(id),
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, pr_id, line_no)
);

CREATE INDEX idx_prs_tenant   ON purchase_requisitions(tenant_id);
CREATE INDEX idx_prs_status   ON purchase_requisitions(tenant_id, status);
CREATE INDEX idx_pr_lines_pr  ON pr_lines(tenant_id, pr_id);
