-- Preserve the labels selected on records when tenant options are renamed or deactivated.
ALTER TABLE items
    ADD COLUMN category_label_snapshot TEXT;

UPDATE items i
SET category_label_snapshot = c.name
FROM item_categories c
WHERE c.id = i.category_id
  AND i.category_label_snapshot IS NULL;

UPDATE job_stages
SET stage_label_snapshot = name
WHERE stage_label_snapshot IS NULL;

ALTER TABLE quality_inspections
    ADD COLUMN inspection_type_option_id UUID REFERENCES tenant_config_options(id),
    ADD COLUMN inspection_type_label_snapshot TEXT;

UPDATE quality_inspections
SET inspection_type_label_snapshot = inspection_type
WHERE inspection_type_label_snapshot IS NULL;

ALTER TABLE ncrs
    ADD COLUMN defect_category_label_snapshot TEXT;

UPDATE ncrs n
SET defect_category_label_snapshot = COALESCE(o.label, n.defect_type)
FROM tenant_config_options o
WHERE o.id = n.defect_category_id
  AND o.tenant_id = n.tenant_id
  AND n.defect_category_label_snapshot IS NULL;

UPDATE ncrs
SET defect_category_label_snapshot = defect_type
WHERE defect_category_label_snapshot IS NULL
  AND defect_type IS NOT NULL;

CREATE INDEX idx_quality_inspection_type_option
    ON quality_inspections (tenant_id, inspection_type_option_id);
CREATE INDEX idx_ncr_defect_category
    ON ncrs (tenant_id, defect_category_id);

INSERT INTO config_categories (code, name, scope) VALUES
    ('DEFECT_CATEGORY', 'Defect Categories', 'tenant')
ON CONFLICT (code) DO NOTHING;
