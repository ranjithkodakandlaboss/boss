CREATE TABLE quotation_costing_config (
  tenant_id UUID PRIMARY KEY REFERENCES tenant_registry(id),
  labour_rates JSONB NOT NULL DEFAULT '[]'::jsonb,
  machine_rates JSONB NOT NULL DEFAULT '[]'::jsonb,
  overhead_per_unit NUMERIC(15,4) NOT NULL DEFAULT 0 CHECK (overhead_per_unit >= 0),
  consumables_per_unit NUMERIC(15,4) NOT NULL DEFAULT 0 CHECK (consumables_per_unit >= 0),
  packaging_per_unit NUMERIC(15,4) NOT NULL DEFAULT 0 CHECK (packaging_per_unit >= 0),
  transport_per_unit NUMERIC(15,4) NOT NULL DEFAULT 0 CHECK (transport_per_unit >= 0),
  target_margin_pct NUMERIC(7,4) NOT NULL DEFAULT 20 CHECK (target_margin_pct BETWEEN 0 AND 100),
  minimum_margin_pct NUMERIC(7,4) NOT NULL DEFAULT 10 CHECK (minimum_margin_pct BETWEEN 0 AND 100),
  approval_threshold_pct NUMERIC(7,4) NOT NULL DEFAULT 15 CHECK (approval_threshold_pct BETWEEN 0 AND 100),
  updated_by UUID NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE quotations
  ADD COLUMN estimated_total_cost NUMERIC(15,2),
  ADD COLUMN estimated_gross_profit NUMERIC(15,2),
  ADD COLUMN estimated_gross_margin_pct NUMERIC(7,4),
  ADD COLUMN costing_snapshot JSONB,
  ADD COLUMN cost_override_reason TEXT,
  ADD COLUMN cost_override_by UUID,
  ADD COLUMN cost_override_at TIMESTAMPTZ;

ALTER TABLE quotation_lines
  ADD COLUMN labour_hours JSONB NOT NULL DEFAULT '{}'::jsonb,
  ADD COLUMN machine_hours JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE quotation_costing_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE quotation_costing_config FORCE ROW LEVEL SECURITY;
CREATE POLICY quotation_costing_config_isolation
  ON quotation_costing_config USING (tenant_id = boss_current_tenant_id());
