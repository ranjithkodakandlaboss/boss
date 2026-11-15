-- V020: Customer Purchase Orders and CPO lines (tenant-scoped)
CREATE TABLE cpos (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  cpo_number       TEXT NOT NULL,
  quotation_id     UUID REFERENCES quotations(id),
  customer_id      UUID NOT NULL REFERENCES customers(id),
  site_id          UUID NOT NULL REFERENCES physical_sites(id),
  status           TEXT NOT NULL DEFAULT 'open'
                     CHECK (status IN ('open','partial','fulfilled','cancelled','on_hold')),
  customer_po_ref  TEXT,
  order_date       DATE NOT NULL,
  required_by_date DATE,
  payment_terms    INTEGER DEFAULT 30,
  billing_address  JSONB,
  shipping_address JSONB,
  notes            TEXT,
  created_by       UUID NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, cpo_number)
);

CREATE TABLE cpo_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  cpo_id          UUID NOT NULL REFERENCES cpos(id),
  quotation_line_id UUID REFERENCES quotation_lines(id),
  line_no         INTEGER NOT NULL,
  item_id         UUID NOT NULL REFERENCES items(id),
  quantity        NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  unit_price      NUMERIC(15,2) NOT NULL,
  gst_rate        NUMERIC(5,2),
  discount_pct    NUMERIC(5,2) DEFAULT 0,
  delivered_qty   NUMERIC(15,3) NOT NULL DEFAULT 0,
  invoiced_qty    NUMERIC(15,3) NOT NULL DEFAULT 0,
  required_by_date DATE,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, cpo_id, line_no)
);

CREATE INDEX idx_cpos_tenant          ON cpos(tenant_id, customer_id);
CREATE INDEX idx_cpos_status          ON cpos(tenant_id, status);
CREATE INDEX idx_cpo_lines_cpo        ON cpo_lines(tenant_id, cpo_id);
