-- V022: Supplier quotations and lines (tenant-scoped)
CREATE TABLE supplier_quotations (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  sq_number        TEXT NOT NULL,
  pr_id            UUID REFERENCES purchase_requisitions(id),
  supplier_id      UUID NOT NULL REFERENCES suppliers(id),
  status           TEXT NOT NULL DEFAULT 'draft'
                     CHECK (status IN ('draft','sent','received','accepted','rejected','expired')),
  valid_until      DATE,
  payment_terms    INTEGER DEFAULT 30,
  notes            TEXT,
  created_by       UUID NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, sq_number)
);

CREATE TABLE supplier_quotation_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  sq_id           UUID NOT NULL REFERENCES supplier_quotations(id),
  pr_line_id      UUID REFERENCES pr_lines(id),
  line_no         INTEGER NOT NULL,
  item_id         UUID NOT NULL REFERENCES items(id),
  quantity        NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  unit_price      NUMERIC(15,2) NOT NULL,
  gst_rate        NUMERIC(5,2),
  delivery_days   INTEGER,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, sq_id, line_no)
);

CREATE INDEX idx_sq_tenant         ON supplier_quotations(tenant_id, supplier_id);
CREATE INDEX idx_sq_lines_sq       ON supplier_quotation_lines(tenant_id, sq_id);
