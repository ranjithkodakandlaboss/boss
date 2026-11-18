-- V097: Preserve each routing template revision as a separate immutable row.
ALTER TABLE routing_templates
  ADD COLUMN revision_number INTEGER NOT NULL DEFAULT 1,
  ADD COLUMN supersedes_id UUID REFERENCES routing_templates(id);

UPDATE routing_templates
SET revision_number = CASE
  WHEN version ~ '^v[1-9][0-9]*$' THEN substring(version FROM 2)::INTEGER
  ELSE 1
END;

ALTER TABLE routing_templates
  ADD CONSTRAINT chk_routing_templates_revision_number CHECK (revision_number > 0);

CREATE INDEX idx_routing_templates_supersedes
  ON routing_templates (tenant_id, supersedes_id)
  WHERE supersedes_id IS NOT NULL;
