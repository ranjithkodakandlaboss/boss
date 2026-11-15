-- V030: Invoice sequence counters (tenant-scoped, pessimistic locking required)
CREATE TABLE invoice_sequence_counters (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  gst_reg_id  UUID NOT NULL REFERENCES gst_registrations(id),
  fiscal_year TEXT NOT NULL,
  prefix      TEXT NOT NULL DEFAULT 'INV',
  last_number INTEGER NOT NULL DEFAULT 0,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, gst_reg_id, fiscal_year, prefix)
);

CREATE INDEX idx_inv_seq_tenant ON invoice_sequence_counters(tenant_id, gst_reg_id);
