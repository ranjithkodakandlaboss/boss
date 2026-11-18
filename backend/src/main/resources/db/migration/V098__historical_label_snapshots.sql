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

ALTER TABLE dispatches
    ADD COLUMN dispatch_method_option_id UUID REFERENCES tenant_config_options(id),
    ADD COLUMN dispatch_method_label_snapshot TEXT;

CREATE INDEX idx_quality_inspection_type_option
    ON quality_inspections (tenant_id, inspection_type_option_id);
CREATE INDEX idx_ncr_defect_category
    ON ncrs (tenant_id, defect_category_id);
CREATE INDEX idx_dispatch_method_option
    ON dispatches (tenant_id, dispatch_method_option_id);

INSERT INTO config_categories (code, name, scope) VALUES
    ('DEFECT_CATEGORY', 'Defect Categories', 'tenant'),
    ('DISPATCH_METHOD', 'Dispatch Methods', 'tenant')
ON CONFLICT (code) DO NOTHING;

CREATE OR REPLACE FUNCTION snapshot_dispatch_method_label()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND NEW.dispatch_method_option_id IS NOT DISTINCT FROM OLD.dispatch_method_option_id THEN
        RETURN NEW;
    END IF;

    IF NEW.dispatch_method_option_id IS NULL THEN
        NEW.dispatch_method_label_snapshot := NULL;
        RETURN NEW;
    END IF;

    SELECT o.label
    INTO NEW.dispatch_method_label_snapshot
    FROM tenant_config_options o
    JOIN config_categories c ON c.id = o.category_id
    WHERE o.id = NEW.dispatch_method_option_id
      AND o.tenant_id = NEW.tenant_id
      AND o.is_active
      AND c.code = 'DISPATCH_METHOD';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Dispatch method is not an active option for this tenant';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER dispatch_method_label_snapshot
    BEFORE INSERT OR UPDATE OF dispatch_method_option_id
    ON dispatches
    FOR EACH ROW
    EXECUTE FUNCTION snapshot_dispatch_method_label();
