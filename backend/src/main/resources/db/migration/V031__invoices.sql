-- V031: Invoices and invoice lines (tenant-scoped)
CREATE TABLE invoices (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  invoice_number   TEXT NOT NULL,
  cpo_id           UUID REFERENCES cpos(id),
  dispatch_id      UUID REFERENCES dispatches(id),
  customer_id      UUID NOT NULL REFERENCES customers(id),
  gst_reg_id       UUID NOT NULL REFERENCES gst_registrations(id),
  invoice_date     DATE NOT NULL,
  due_date         DATE,
  status           TEXT NOT NULL DEFAULT 'draft'
                     CHECK (status IN ('draft','issued','paid','partially_paid',
                                       'overdue','cancelled','credit_note')),
  invoice_type     TEXT NOT NULL DEFAULT 'tax_invoice'
                     CHECK (invoice_type IN ('tax_invoice','proforma','credit_note','debit_note')),
  taxable_amount   NUMERIC(15,2) NOT NULL DEFAULT 0,
  cgst_amount      NUMERIC(15,2) NOT NULL DEFAULT 0,
  sgst_amount      NUMERIC(15,2) NOT NULL DEFAULT 0,
  igst_amount      NUMERIC(15,2) NOT NULL DEFAULT 0,
  total_amount     NUMERIC(15,2) NOT NULL DEFAULT 0,
  paid_amount      NUMERIC(15,2) NOT NULL DEFAULT 0,
  irn              TEXT,
  ack_number       TEXT,
  qr_code_data     TEXT,
  notes            TEXT,
  created_by       UUID NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, invoice_number)
);

CREATE TABLE invoice_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  invoice_id      UUID NOT NULL REFERENCES invoices(id),
  dispatch_line_id UUID REFERENCES dispatch_lines(id),
  cpo_line_id     UUID REFERENCES cpo_lines(id),
  line_no         INTEGER NOT NULL,
  item_id         UUID NOT NULL REFERENCES items(id),
  description     TEXT,
  quantity        NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  unit_price      NUMERIC(15,2) NOT NULL,
  discount_pct    NUMERIC(5,2) DEFAULT 0,
  taxable_amount  NUMERIC(15,2) NOT NULL,
  gst_rate        NUMERIC(5,2),
  cgst_amount     NUMERIC(15,2) NOT NULL DEFAULT 0,
  sgst_amount     NUMERIC(15,2) NOT NULL DEFAULT 0,
  igst_amount     NUMERIC(15,2) NOT NULL DEFAULT 0,
  line_total      NUMERIC(15,2) NOT NULL,
  hsn_code        TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, invoice_id, line_no)
);

CREATE INDEX idx_invoices_tenant    ON invoices(tenant_id, customer_id);
CREATE INDEX idx_invoices_status    ON invoices(tenant_id, status);
CREATE INDEX idx_invoice_lines_inv  ON invoice_lines(tenant_id, invoice_id);
