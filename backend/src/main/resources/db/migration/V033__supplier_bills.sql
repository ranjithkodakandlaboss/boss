-- V033: Supplier bills and payments (tenant-scoped)
CREATE TABLE supplier_bills (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  bill_number      TEXT NOT NULL,
  spo_id           UUID REFERENCES spos(id),
  grn_id           UUID REFERENCES grns(id),
  supplier_id      UUID NOT NULL REFERENCES suppliers(id),
  supplier_invoice_no TEXT,
  invoice_date     DATE NOT NULL,
  due_date         DATE,
  status           TEXT NOT NULL DEFAULT 'draft'
                     CHECK (status IN ('draft','verified','approved','paid','partially_paid',
                                       'overdue','disputed','cancelled')),
  taxable_amount   NUMERIC(15,2) NOT NULL DEFAULT 0,
  gst_amount       NUMERIC(15,2) NOT NULL DEFAULT 0,
  total_amount     NUMERIC(15,2) NOT NULL DEFAULT 0,
  paid_amount      NUMERIC(15,2) NOT NULL DEFAULT 0,
  notes            TEXT,
  created_by       UUID NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, bill_number)
);

CREATE TABLE supplier_payments (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  payment_number   TEXT NOT NULL,
  bill_id          UUID NOT NULL REFERENCES supplier_bills(id),
  supplier_id      UUID NOT NULL REFERENCES suppliers(id),
  amount           NUMERIC(15,2) NOT NULL CHECK (amount > 0),
  payment_date     DATE NOT NULL,
  payment_mode     TEXT NOT NULL
                     CHECK (payment_mode IN ('bank_transfer','cheque','upi','cash','other')),
  reference_number TEXT,
  notes            TEXT,
  created_by       UUID NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, payment_number)
);

CREATE INDEX idx_supplier_bills_tenant ON supplier_bills(tenant_id, supplier_id);
CREATE INDEX idx_supplier_bills_status ON supplier_bills(tenant_id, status);
CREATE INDEX idx_supplier_payments     ON supplier_payments(tenant_id, bill_id);
