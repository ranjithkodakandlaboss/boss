-- V032: Receipts (customer payments) and allocations (tenant-scoped)
CREATE TABLE receipts (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  receipt_number   TEXT NOT NULL,
  customer_id      UUID NOT NULL REFERENCES customers(id),
  amount           NUMERIC(15,2) NOT NULL CHECK (amount > 0),
  payment_date     DATE NOT NULL,
  payment_mode     TEXT NOT NULL
                     CHECK (payment_mode IN ('bank_transfer','cheque','upi','cash','other')),
  reference_number TEXT,
  bank_account     TEXT,
  status           TEXT NOT NULL DEFAULT 'received'
                     CHECK (status IN ('received','allocated','reversed')),
  unallocated_amount NUMERIC(15,2) NOT NULL,
  notes            TEXT,
  created_by       UUID NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, receipt_number)
);

CREATE TABLE receipt_allocations (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  receipt_id      UUID NOT NULL REFERENCES receipts(id),
  invoice_id      UUID NOT NULL REFERENCES invoices(id),
  amount          NUMERIC(15,2) NOT NULL CHECK (amount > 0),
  allocated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  allocated_by    UUID NOT NULL
);

CREATE INDEX idx_receipts_tenant      ON receipts(tenant_id, customer_id);
CREATE INDEX idx_receipt_alloc_recv   ON receipt_allocations(tenant_id, receipt_id);
CREATE INDEX idx_receipt_alloc_inv    ON receipt_allocations(tenant_id, invoice_id);
