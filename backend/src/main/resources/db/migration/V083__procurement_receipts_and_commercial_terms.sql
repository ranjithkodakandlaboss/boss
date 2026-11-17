-- Commercial terms the quotation form records, plus the receipt, return,
-- amendment, and material-availability decisions the procurement flow requires.
-- Over-receipt is rejected in the service because no receipt tolerance is configured.

ALTER TABLE supplier_quotations
  ADD COLUMN IF NOT EXISTS supplier_quote_ref TEXT,
  ADD COLUMN IF NOT EXISTS freight_amount NUMERIC(15,2) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS discount_amount NUMERIC(15,2) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS tax_amount NUMERIC(15,2) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS selection_reason TEXT;

ALTER TABLE supplier_quotation_lines
  ADD COLUMN IF NOT EXISTS tax_rate NUMERIC(5,2);

ALTER TABLE spos DROP CONSTRAINT IF EXISTS spos_status_check;
ALTER TABLE spos
  ADD CONSTRAINT spos_status_check
  CHECK (status IN (
    'draft', 'issued', 'acknowledged', 'declined',
    'partially_received', 'fully_received', 'cancelled'
  ));

ALTER TABLE spos
  ADD COLUMN IF NOT EXISTS revision INTEGER NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS decline_reason TEXT;

CREATE TABLE IF NOT EXISTS spo_amendments (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     UUID NOT NULL REFERENCES tenant_registry(id),
  spo_id        UUID NOT NULL REFERENCES spos(id),
  revision      INTEGER NOT NULL,
  reason        TEXT NOT NULL,
  change_note   TEXT NOT NULL,
  line_id       UUID,
  quantity      NUMERIC(15,3),
  unit_price    NUMERIC(15,2),
  expected_date DATE,
  payment_terms INTEGER,
  status        TEXT NOT NULL DEFAULT 'pending_approval'
                  CHECK (status IN ('pending_approval', 'applied', 'rejected')),
  created_by    UUID NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, spo_id, revision)
);

CREATE TABLE IF NOT EXISTS supplier_returns (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID NOT NULL REFERENCES tenant_registry(id),
  return_number  TEXT NOT NULL,
  grn_id         UUID NOT NULL REFERENCES grns(id),
  spo_id         UUID REFERENCES spos(id),
  supplier_id    UUID NOT NULL REFERENCES suppliers(id),
  status         TEXT NOT NULL DEFAULT 'posted'
                   CHECK (status IN ('posted', 'cancelled')),
  reason         TEXT NOT NULL,
  created_by     UUID NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, return_number)
);

CREATE TABLE IF NOT EXISTS supplier_return_lines (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    UUID NOT NULL REFERENCES tenant_registry(id),
  return_id    UUID NOT NULL REFERENCES supplier_returns(id),
  grn_line_id  UUID NOT NULL REFERENCES grn_lines(id),
  lot_id       UUID NOT NULL REFERENCES material_lots(id),
  item_id      UUID NOT NULL REFERENCES items(id),
  quantity     NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id       UUID NOT NULL REFERENCES units_of_measure(id),
  reason       TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, return_id, lot_id)
);

ALTER TABLE cpo_lines
  ADD COLUMN IF NOT EXISTS availability TEXT,
  ADD COLUMN IF NOT EXISTS shortage_qty NUMERIC(15,3);

ALTER TABLE cpo_lines DROP CONSTRAINT IF EXISTS cpo_lines_availability_check;
ALTER TABLE cpo_lines
  ADD CONSTRAINT cpo_lines_availability_check
  CHECK (availability IS NULL OR availability IN ('available', 'partial', 'unavailable'));

ALTER TABLE spo_amendments ENABLE ROW LEVEL SECURITY;
ALTER TABLE spo_amendments FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS spo_amendments_isolation ON spo_amendments;
CREATE POLICY spo_amendments_isolation ON spo_amendments
  USING (tenant_id = boss_current_tenant_id());

ALTER TABLE supplier_returns ENABLE ROW LEVEL SECURITY;
ALTER TABLE supplier_returns FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS supplier_returns_isolation ON supplier_returns;
CREATE POLICY supplier_returns_isolation ON supplier_returns
  USING (tenant_id = boss_current_tenant_id());

ALTER TABLE supplier_return_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE supplier_return_lines FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS supplier_return_lines_isolation ON supplier_return_lines;
CREATE POLICY supplier_return_lines_isolation ON supplier_return_lines
  USING (tenant_id = boss_current_tenant_id());

CREATE INDEX IF NOT EXISTS idx_spo_amendments_spo ON spo_amendments(tenant_id, spo_id);
CREATE INDEX IF NOT EXISTS idx_supplier_returns_grn ON supplier_returns(tenant_id, grn_id);
CREATE INDEX IF NOT EXISTS idx_supplier_return_lines_return ON supplier_return_lines(tenant_id, return_id);
