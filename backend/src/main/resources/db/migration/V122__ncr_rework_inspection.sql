ALTER TABLE ncrs
  DROP CONSTRAINT IF EXISTS ncrs_status_check;

ALTER TABLE ncrs
  ADD CONSTRAINT ncrs_status_check
    CHECK (status IN ('open', 'under_review', 'resolved', 'closed', 'scrapped', 'rework'));

ALTER TABLE ncrs
  ADD COLUMN rework_inspection_id UUID REFERENCES quality_inspections(id);

CREATE INDEX idx_ncrs_rework_inspection
  ON ncrs (tenant_id, rework_inspection_id)
  WHERE rework_inspection_id IS NOT NULL;
