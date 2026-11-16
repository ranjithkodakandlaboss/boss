-- V070: Purchase Requisition lifecycle alignment (Issue #47)

-- 1. Status lifecycle constraint
ALTER TABLE purchase_requisitions
  DROP CONSTRAINT IF EXISTS purchase_requisitions_status_check;

ALTER TABLE purchase_requisitions
  ADD CONSTRAINT purchase_requisitions_status_check
  CHECK (status IN ('draft', 'submitted', 'approved', 'partially_ordered', 'ordered', 'rejected', 'cancelled'));

-- 2. Reference type constraint compatibility
ALTER TABLE purchase_requisitions
  DROP CONSTRAINT IF EXISTS purchase_requisitions_reference_type_check;

ALTER TABLE purchase_requisitions
  ADD CONSTRAINT purchase_requisitions_reference_type_check
  CHECK (reference_type IS NULL OR reference_type IN ('production_job', 'manual', 'reorder', 'stock_driven'));

-- 3. Origin type and references
ALTER TABLE purchase_requisitions
  ADD COLUMN IF NOT EXISTS origin_type TEXT NOT NULL DEFAULT 'manual'
    CHECK (origin_type IN ('manual', 'production_job', 'stock_driven')),
  ADD COLUMN IF NOT EXISTS job_id UUID REFERENCES production_jobs(id),
  ADD COLUMN IF NOT EXISTS pr_type_id UUID,
  ADD COLUMN IF NOT EXISTS pr_type_label TEXT,
  ADD COLUMN IF NOT EXISTS rejection_reason TEXT,
  ADD COLUMN IF NOT EXISTS rejected_by UUID,
  ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMPTZ;

-- Backfill origin_type and job_id from legacy reference columns if present
UPDATE purchase_requisitions
  SET origin_type = CASE
    WHEN reference_type = 'reorder' THEN 'stock_driven'
    WHEN reference_type IN ('production_job', 'manual') THEN reference_type
    ELSE 'manual'
  END
  WHERE origin_type = 'manual' AND reference_type IS NOT NULL;

UPDATE purchase_requisitions
  SET job_id = reference_id
  WHERE job_id IS NULL AND reference_type = 'production_job' AND reference_id IS NOT NULL;

-- 4. PR lines extensions
ALTER TABLE pr_lines
  ADD COLUMN IF NOT EXISTS required_date DATE,
  ADD COLUMN IF NOT EXISTS ordered_qty NUMERIC(15,3) NOT NULL DEFAULT 0;

-- 5. Indexes
CREATE INDEX IF NOT EXISTS idx_prs_origin ON purchase_requisitions(tenant_id, origin_type);
CREATE INDEX IF NOT EXISTS idx_prs_job_id ON purchase_requisitions(tenant_id, job_id);
