-- V018: RFQs and RFQ lines (tenant-scoped)
CREATE TABLE rfqs (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  rfq_number       TEXT NOT NULL,
  customer_id      UUID NOT NULL REFERENCES customers(id),
  site_id          UUID NOT NULL REFERENCES physical_sites(id),
  status           TEXT NOT NULL DEFAULT 'draft'
                     CHECK (status IN ('draft','sent','closed','cancelled')),
  received_date    DATE NOT NULL,
  required_by_date DATE,
  customer_ref     TEXT,
  notes            TEXT,
  created_by       UUID NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, rfq_number)
);

CREATE TABLE rfq_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  rfq_id          UUID NOT NULL REFERENCES rfqs(id),
  line_no         INTEGER NOT NULL,
  item_id         UUID NOT NULL REFERENCES items(id),
  quantity        NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  required_by     DATE,
  specifications  TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, rfq_id, line_no)
);

CREATE INDEX idx_rfqs_tenant      ON rfqs(tenant_id, customer_id);
CREATE INDEX idx_rfq_lines_rfq    ON rfq_lines(tenant_id, rfq_id);
