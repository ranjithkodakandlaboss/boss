-- V087: Production traceability snapshots.
-- Additive only. Existing production rows keep their current columns and statuses.
ALTER TABLE production_jobs
  ADD COLUMN cpo_id UUID REFERENCES cpos(id),
  ADD COLUMN routing_version TEXT,
  ADD COLUMN selling_price NUMERIC(15,2),
  ADD COLUMN hold_reason TEXT,
  ADD COLUMN cancel_reason TEXT;

CREATE UNIQUE INDEX uq_production_jobs_open_cpo_line
  ON production_jobs (tenant_id, cpo_line_id)
  WHERE cpo_line_id IS NOT NULL AND status <> 'cancelled';

ALTER TABLE job_stages
  ADD COLUMN stage_code TEXT,
  ADD COLUMN stage_config_option_id UUID REFERENCES tenant_config_options(id),
  ADD COLUMN stage_label_snapshot TEXT,
  ADD COLUMN requires_inspection BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN inspection_template_id UUID REFERENCES inspection_parameter_templates(id),
  ADD COLUMN is_optional BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN work_centre TEXT,
  ADD COLUMN completed_by UUID;

ALTER TABLE job_material_issues
  ADD COLUMN movement_id UUID REFERENCES stock_movements(id),
  ADD COLUMN unit_cost NUMERIC(15,4),
  ADD COLUMN source_issue_id UUID REFERENCES job_material_issues(id);

ALTER TABLE job_costing_headers
  ADD COLUMN selling_price NUMERIC(15,2),
  ADD COLUMN actual_margin_pct NUMERIC(7,4);

CREATE INDEX idx_job_mat_iss_source ON job_material_issues (tenant_id, source_issue_id);
