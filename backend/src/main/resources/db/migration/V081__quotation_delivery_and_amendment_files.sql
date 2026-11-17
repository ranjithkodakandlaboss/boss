-- Supplier quotation email attempts and the evidence stored with an SPO amendment.
-- A sent delivery is unique per quotation so a retry cannot send a second copy.

CREATE TABLE IF NOT EXISTS supplier_quotation_deliveries (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           UUID NOT NULL REFERENCES tenant_registry(id),
  quotation_id        UUID NOT NULL REFERENCES supplier_quotations(id),
  recipient           TEXT,
  status              TEXT NOT NULL CHECK (status IN ('sent', 'failed')),
  provider_message_id TEXT,
  error_message       TEXT,
  attempt_no          INTEGER NOT NULL CHECK (attempt_no > 0),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, quotation_id, attempt_no)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_sq_delivery_sent
  ON supplier_quotation_deliveries(tenant_id, quotation_id)
  WHERE status = 'sent';

CREATE UNIQUE INDEX IF NOT EXISTS uq_sq_open_request
  ON supplier_quotations(tenant_id, pr_id, supplier_id)
  WHERE status = 'requested';

CREATE TABLE IF NOT EXISTS spo_amendment_files (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     UUID NOT NULL REFERENCES tenant_registry(id),
  amendment_id  UUID NOT NULL REFERENCES spo_amendments(id),
  file_name     TEXT NOT NULL,
  content_type  TEXT NOT NULL,
  size_bytes    INTEGER NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 5242880),
  content       BYTEA NOT NULL,
  created_by    UUID NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE supplier_quotation_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE supplier_quotation_deliveries FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS supplier_quotation_deliveries_isolation ON supplier_quotation_deliveries;
CREATE POLICY supplier_quotation_deliveries_isolation ON supplier_quotation_deliveries
  USING (tenant_id = boss_current_tenant_id());

ALTER TABLE spo_amendment_files ENABLE ROW LEVEL SECURITY;
ALTER TABLE spo_amendment_files FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS spo_amendment_files_isolation ON spo_amendment_files;
CREATE POLICY spo_amendment_files_isolation ON spo_amendment_files
  USING (tenant_id = boss_current_tenant_id());

CREATE INDEX IF NOT EXISTS idx_sq_deliveries_quote
  ON supplier_quotation_deliveries(tenant_id, quotation_id, attempt_no DESC);
CREATE INDEX IF NOT EXISTS idx_spo_amendment_files_amendment
  ON spo_amendment_files(tenant_id, amendment_id);
