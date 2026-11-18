-- V096: Dispatch lifecycle alignment, packing records, and transport config categories
-- Expands dispatches status check to align with prototype and OpenAPI lifecycle states:
-- ('draft', 'pending', 'pending_docs', 'packed', 'dispatched', 'in_transit', 'delayed', 'delivered', 'cancelled')

ALTER TABLE dispatches DROP CONSTRAINT IF EXISTS dispatches_status_check;
ALTER TABLE dispatches ADD CONSTRAINT dispatches_status_check
  CHECK (status IN ('draft', 'pending', 'pending_docs', 'packed', 'dispatched', 'in_transit', 'delayed', 'delivered', 'cancelled'));

ALTER TABLE dispatches
  ADD COLUMN IF NOT EXISTS carrier TEXT,
  ADD COLUMN IF NOT EXISTS driver_name TEXT,
  ADD COLUMN IF NOT EXISTS expected_delivery DATE,
  ADD COLUMN IF NOT EXISTS actual_delivery_date DATE,
  ADD COLUMN IF NOT EXISTS delivery_condition TEXT,
  ADD COLUMN IF NOT EXISTS pod_reference TEXT,
  ADD COLUMN IF NOT EXISTS delivery_notes TEXT,
  ADD COLUMN IF NOT EXISTS has_pod BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS source_store_id UUID REFERENCES stores(id),
  ADD COLUMN IF NOT EXISTS source_store_name TEXT,
  ADD COLUMN IF NOT EXISTS quality_release_id UUID,
  ADD COLUMN IF NOT EXISTS job_id UUID REFERENCES production_jobs(id),
  ADD COLUMN IF NOT EXISTS challan_number TEXT,
  ADD COLUMN IF NOT EXISTS challan_gcs_path TEXT,
  ADD COLUMN IF NOT EXISTS dispatched_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS total_qty NUMERIC(15,3) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS line_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE dispatch_lines
  ADD COLUMN IF NOT EXISTS job_id UUID REFERENCES production_jobs(id),
  ADD COLUMN IF NOT EXISTS clearance_cert_id UUID;

ALTER TABLE pod_records
  ADD COLUMN IF NOT EXISTS delivered_date DATE,
  ADD COLUMN IF NOT EXISTS condition TEXT,
  ADD COLUMN IF NOT EXISTS pod_reference TEXT;

CREATE TABLE IF NOT EXISTS dispatch_packages (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           UUID NOT NULL REFERENCES tenant_registry(id),
  dispatch_id         UUID NOT NULL REFERENCES dispatches(id) ON DELETE CASCADE,
  package_code        TEXT NOT NULL,
  package_type        TEXT NOT NULL,
  product_description TEXT,
  quantity            NUMERIC(15,3) NOT NULL DEFAULT 1,
  uom                 TEXT,
  weight_kg           NUMERIC(10,3),
  length_mm           NUMERIC(10,2),
  width_mm            NUMERIC(10,2),
  height_mm           NUMERIC(10,2),
  packing_notes       TEXT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE dispatch_packages ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch_packages FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE tablename = 'dispatch_packages' AND policyname = 'dispatch_packages_isolation'
  ) THEN
    CREATE POLICY dispatch_packages_isolation ON dispatch_packages USING (tenant_id = boss_current_tenant_id());
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_dispatch_packages_disp ON dispatch_packages(tenant_id, dispatch_id);

INSERT INTO config_categories (code, name, scope) VALUES
  ('CARRIER',      'Carriers / Transporters', 'tenant'),
  ('VEHICLE',      'Vehicles',                'tenant'),
  ('DRIVER',       'Drivers',                 'tenant'),
  ('PACKAGE_TYPE', 'Package Types',           'tenant')
ON CONFLICT (code) DO NOTHING;
