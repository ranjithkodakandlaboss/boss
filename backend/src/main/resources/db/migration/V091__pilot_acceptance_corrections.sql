-- Pilot acceptance: payment terms as entered, quotation required-by,
-- supplier PO charges, asset capabilities, and stage-to-asset assignment.

ALTER TABLE quotations ADD COLUMN IF NOT EXISTS payment_terms_text VARCHAR(200);
ALTER TABLE cpos ADD COLUMN IF NOT EXISTS payment_terms_text VARCHAR(200);
ALTER TABLE supplier_quotations ADD COLUMN IF NOT EXISTS payment_terms_text VARCHAR(200);
ALTER TABLE spos ADD COLUMN IF NOT EXISTS payment_terms_text VARCHAR(200);
ALTER TABLE spos ADD COLUMN IF NOT EXISTS freight_amount NUMERIC(18,2) NOT NULL DEFAULT 0;
ALTER TABLE spos ADD COLUMN IF NOT EXISTS tax_amount NUMERIC(18,2) NOT NULL DEFAULT 0;

ALTER TABLE quotation_lines ADD COLUMN IF NOT EXISTS required_by DATE;

ALTER TABLE job_stages ADD COLUMN IF NOT EXISTS asset_id UUID REFERENCES assets(id);
ALTER TABLE routing_template_stages ADD COLUMN IF NOT EXISTS work_centre TEXT;
ALTER TABLE routing_template_stages ADD COLUMN IF NOT EXISTS asset_id UUID REFERENCES assets(id);

CREATE TABLE IF NOT EXISTS asset_capabilities (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant_registry(id),
  asset_id UUID NOT NULL REFERENCES assets(id),
  stage_option_id UUID NOT NULL REFERENCES tenant_config_options(id),
  stage_label VARCHAR(200) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, asset_id, stage_option_id)
);

CREATE INDEX IF NOT EXISTS idx_asset_capabilities_asset ON asset_capabilities(tenant_id, asset_id);
CREATE INDEX IF NOT EXISTS idx_job_stages_asset ON job_stages(tenant_id, asset_id);

ALTER TABLE asset_capabilities ENABLE ROW LEVEL SECURITY;
ALTER TABLE asset_capabilities FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS asset_capabilities_isolation ON asset_capabilities;
CREATE POLICY asset_capabilities_isolation ON asset_capabilities
  USING (tenant_id = boss_current_tenant_id())
  WITH CHECK (tenant_id = boss_current_tenant_id());
