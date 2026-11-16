-- V019: Quotations, quotation lines, quotation revisions (tenant-scoped)
CREATE TABLE quotations (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  quotation_number TEXT NOT NULL,
  rfq_id           UUID REFERENCES rfqs(id),
  customer_id      UUID NOT NULL REFERENCES customers(id),
  site_id          UUID NOT NULL REFERENCES physical_sites(id),
  revision         INTEGER NOT NULL DEFAULT 1,
  status           TEXT NOT NULL DEFAULT 'draft'
                     CHECK (status IN ('draft','sent','accepted','rejected','expired','superseded')),
  valid_until      DATE,
  payment_terms    INTEGER DEFAULT 30,
  delivery_terms   TEXT,
  notes            TEXT,
  created_by       UUID NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, quotation_number, revision)
);

CREATE TABLE quotation_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  quotation_id    UUID NOT NULL REFERENCES quotations(id),
  rfq_line_id     UUID REFERENCES rfq_lines(id),
  line_no         INTEGER NOT NULL,
  item_id         UUID NOT NULL REFERENCES items(id),
  quantity        NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  unit_price      NUMERIC(15,2) NOT NULL,
  gst_rate        NUMERIC(5,2),
  discount_pct    NUMERIC(5,2) DEFAULT 0,
  delivery_days   INTEGER,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, quotation_id, line_no)
);

CREATE TABLE quotation_revisions (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     UUID NOT NULL REFERENCES tenant_registry(id),
  quotation_id  UUID NOT NULL REFERENCES quotations(id),
  revision      INTEGER NOT NULL,
  changed_by    UUID NOT NULL,
  change_reason TEXT,
  snapshot      JSONB NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_quotations_tenant     ON quotations(tenant_id, customer_id);
CREATE INDEX idx_quotation_lines_quot  ON quotation_lines(tenant_id, quotation_id);
