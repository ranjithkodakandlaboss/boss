-- V024: GRNs (Goods Receipt Notes) and GRN lines (tenant-scoped)
CREATE TABLE grns (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  grn_number      TEXT NOT NULL,
  spo_id          UUID REFERENCES spos(id),
  supplier_id     UUID NOT NULL REFERENCES suppliers(id),
  site_id         UUID NOT NULL REFERENCES physical_sites(id),
  status          TEXT NOT NULL DEFAULT 'draft'
                    CHECK (status IN ('draft','inspected','accepted','partially_accepted','rejected','closed')),
  received_date   DATE NOT NULL,
  supplier_dc_ref TEXT,
  vehicle_number  TEXT,
  notes           TEXT,
  received_by     UUID NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, grn_number)
);

CREATE TABLE grn_lines (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         UUID NOT NULL REFERENCES tenant_registry(id),
  grn_id            UUID NOT NULL REFERENCES grns(id),
  spo_line_id       UUID REFERENCES spo_lines(id),
  line_no           INTEGER NOT NULL,
  item_id           UUID NOT NULL REFERENCES items(id),
  received_qty      NUMERIC(15,3) NOT NULL CHECK (received_qty >= 0),
  accepted_qty      NUMERIC(15,3),
  rejected_qty      NUMERIC(15,3),
  uom_id            UUID NOT NULL REFERENCES units_of_measure(id),
  unit_price        NUMERIC(15,2),
  store_id          UUID NOT NULL REFERENCES stores(id),
  lot_number        TEXT,
  batch_number      TEXT,
  expiry_date       DATE,
  rejection_reason  TEXT,
  notes             TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, grn_id, line_no)
);

-- FK back-ref from supplier_performance_records
ALTER TABLE supplier_performance_records
  ADD CONSTRAINT fk_spr_grn FOREIGN KEY (grn_id) REFERENCES grns(id);

CREATE INDEX idx_grns_tenant   ON grns(tenant_id, supplier_id);
CREATE INDEX idx_grns_status   ON grns(tenant_id, status);
CREATE INDEX idx_grn_lines_grn ON grn_lines(tenant_id, grn_id);
