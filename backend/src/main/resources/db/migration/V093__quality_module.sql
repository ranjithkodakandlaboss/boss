-- V091: Quality module additions
--   1. Enhance inspection_parameter_templates for checklist management
--   2. Add checklist_items to store ordered items within a template
--   3. Add inspection_checklist_responses for snapshotted inspection results
--   4. Add clearance_certificates for customer quality clearance
--   5. RLS and indexes

-- Extend inspection_parameter_templates to support admin-managed checklists
ALTER TABLE inspection_parameter_templates
  ADD COLUMN IF NOT EXISTS description       TEXT,
  ADD COLUMN IF NOT EXISTS scope             TEXT CHECK (scope IN ('material', 'job')),
  ADD COLUMN IF NOT EXISTS inspection_type   TEXT CHECK (inspection_type IN ('incoming','in_process','final','dispatch_readiness','any')),
  ADD COLUMN IF NOT EXISTS is_active         BOOLEAN NOT NULL DEFAULT TRUE;

-- checklist_items — ordered items within a template (replaces raw parameter_name on the template row)
CREATE TABLE IF NOT EXISTS checklist_items (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  template_id      UUID NOT NULL REFERENCES inspection_parameter_templates(id) ON DELETE CASCADE,
  sort_order       INTEGER NOT NULL DEFAULT 0,
  description      TEXT NOT NULL,
  response_type    TEXT NOT NULL CHECK (response_type IN ('pass_fail','yes_no','numeric','text')),
  is_mandatory     BOOLEAN NOT NULL DEFAULT TRUE,
  unit             TEXT,
  spec_min         NUMERIC(15,4),
  spec_max         NUMERIC(15,4),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- inspection_checklist_responses — snapshot of checklist items and responses at time of inspection
CREATE TABLE IF NOT EXISTS inspection_checklist_responses (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  inspection_id    UUID NOT NULL REFERENCES quality_inspections(id),
  checklist_item_id UUID NOT NULL REFERENCES checklist_items(id),
  -- snapshot of item text at inspection time (preserves history when template is later edited)
  item_description_snapshot TEXT NOT NULL,
  response_type    TEXT NOT NULL,
  is_mandatory     BOOLEAN NOT NULL DEFAULT TRUE,
  response_value   TEXT,
  response_pass    BOOLEAN,
  observation      TEXT,
  responded_at     TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- clearance_certificates — customer quality clearance before dispatch
CREATE TABLE IF NOT EXISTS clearance_certificates (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  cert_number      TEXT NOT NULL,
  job_id           UUID NOT NULL,
  customer_id      UUID NOT NULL REFERENCES customers(id),
  status           TEXT NOT NULL DEFAULT 'pending'
                     CHECK (status IN ('pending','issued','expired','revoked')),
  issued_at        TIMESTAMPTZ,
  issued_by        UUID REFERENCES app_users(id),
  issued_by_customer_contact TEXT,
  customer_ref     TEXT,
  notes            TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, cert_number)
);

-- Add dispatch_readiness inspection type to quality_inspections
ALTER TABLE quality_inspections
  DROP CONSTRAINT IF EXISTS quality_inspections_inspection_type_check;

ALTER TABLE quality_inspections
  ADD CONSTRAINT quality_inspections_inspection_type_check
    CHECK (inspection_type IN ('incoming','in_process','final','customer','dispatch_readiness'));

-- Add severity column to ncrs for the UI
ALTER TABLE ncrs
  ADD COLUMN IF NOT EXISTS severity TEXT DEFAULT 'medium'
    CHECK (severity IN ('critical','major','minor','medium'));

-- Add job_id soft-reference to ncrs for context
ALTER TABLE ncrs
  ADD COLUMN IF NOT EXISTS job_id UUID;

ALTER TABLE ncrs
  ADD COLUMN IF NOT EXISTS disposition TEXT;

-- Add clearance_certificate_id reference on quality_inspections for dispatch_readiness
ALTER TABLE quality_inspections
  ADD COLUMN IF NOT EXISTS clearance_certificate_id UUID REFERENCES clearance_certificates(id);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_checklist_items_template    ON checklist_items(tenant_id, template_id, sort_order);
CREATE INDEX IF NOT EXISTS idx_checklist_resp_inspection   ON inspection_checklist_responses(tenant_id, inspection_id);
CREATE INDEX IF NOT EXISTS idx_clearance_certs_job         ON clearance_certificates(tenant_id, job_id);
CREATE INDEX IF NOT EXISTS idx_clearance_certs_customer    ON clearance_certificates(tenant_id, customer_id);

-- RLS
ALTER TABLE checklist_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY checklist_items_isolation ON checklist_items
  USING (tenant_id = boss_current_tenant_id());
ALTER TABLE checklist_items FORCE ROW LEVEL SECURITY;

ALTER TABLE inspection_checklist_responses ENABLE ROW LEVEL SECURITY;
CREATE POLICY checklist_resp_isolation ON inspection_checklist_responses
  USING (tenant_id = boss_current_tenant_id());
ALTER TABLE inspection_checklist_responses FORCE ROW LEVEL SECURITY;

ALTER TABLE clearance_certificates ENABLE ROW LEVEL SECURITY;
CREATE POLICY clearance_certs_isolation ON clearance_certificates
  USING (tenant_id = boss_current_tenant_id());
ALTER TABLE clearance_certificates FORCE ROW LEVEL SECURITY;

-- Grant to app role
GRANT SELECT, INSERT, UPDATE ON checklist_items TO boss_app_role;
GRANT SELECT, INSERT, UPDATE ON inspection_checklist_responses TO boss_app_role;
GRANT SELECT, INSERT, UPDATE ON clearance_certificates TO boss_app_role;
