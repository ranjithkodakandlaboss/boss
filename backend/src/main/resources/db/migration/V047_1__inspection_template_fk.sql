-- V047_1: Add inspection_template_id FK to quality_inspections (issue #95)
ALTER TABLE quality_inspections
  ADD COLUMN inspection_template_id UUID REFERENCES inspection_parameter_templates(id);

CREATE INDEX idx_quality_insp_template ON quality_inspections(tenant_id, inspection_template_id);
