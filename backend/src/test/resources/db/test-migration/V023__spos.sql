-- V023: Supplier Purchase Orders and SPO lines (tenant-scoped)
CREATE TABLE spos (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  spo_number       TEXT NOT NULL,
  sq_id            UUID REFERENCES supplier_quotations(id),
  pr_id            UUID REFERENCES purchase_requisitions(id),
  supplier_id      UUID NOT NULL REFERENCES suppliers(id),
  site_id          UUID NOT NULL REFERENCES physical_sites(id),
  status           TEXT NOT NULL DEFAULT 'open'
                     CHECK (status IN ('open','partial','fulfilled','cancelled','closed')),
  order_date       DATE NOT NULL,
  expected_delivery DATE,
  payment_terms    INTEGER DEFAULT 30,
  delivery_address JSONB,
  notes            TEXT,
  created_by       UUID NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, spo_number)
);

CREATE TABLE spo_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  spo_id          UUID NOT NULL REFERENCES spos(id),
  sq_line_id      UUID REFERENCES supplier_quotation_lines(id),
  pr_line_id      UUID REFERENCES pr_lines(id),
  line_no         INTEGER NOT NULL,
  item_id         UUID NOT NULL REFERENCES items(id),
  quantity        NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  unit_price      NUMERIC(15,2) NOT NULL,
  gst_rate        NUMERIC(5,2),
  received_qty    NUMERIC(15,3) NOT NULL DEFAULT 0,
  expected_date   DATE,
  store_id        UUID REFERENCES stores(id),
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, spo_id, line_no)
);

-- FK back-refs added after V022
ALTER TABLE supplier_performance_records
  ADD CONSTRAINT fk_spr_spo FOREIGN KEY (spo_id) REFERENCES spos(id);

CREATE INDEX idx_spos_tenant          ON spos(tenant_id, supplier_id);
CREATE INDEX idx_spos_status          ON spos(tenant_id, status);
CREATE INDEX idx_spo_lines_spo        ON spo_lines(tenant_id, spo_id);
