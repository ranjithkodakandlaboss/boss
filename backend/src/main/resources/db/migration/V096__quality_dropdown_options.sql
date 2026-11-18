-- Quality and Inspection dropdowns.
--
-- The customer Quality screen loads these category codes:
--   INSPECTION_TYPE, DEFECT_CATEGORY, DEFECT_SEVERITY
-- Industry templates only seeded INSPECTION_TYPES and REJECTION_REASONS, so
-- those three lists came back empty.
--
-- Inspection type and severity are stored as codes, and the table checks only
-- accept the codes below. Defect category is stored as the option id, so its
-- labels follow each industry's existing rejection reasons.
-- Safe to run again: existing rows are left unchanged.

INSERT INTO config_categories (code, name, description, scope) VALUES
  ('INSPECTION_TYPE', 'Inspection Type',
   'Inspection record type. Codes must match quality_inspections_inspection_type_check.',
   'tenant'),
  ('DEFECT_CATEGORY', 'Defect Category',
   'NCR defect category. The NCR stores this option id.',
   'tenant'),
  ('DEFECT_SEVERITY', 'Defect Severity',
   'NCR severity. Codes must match the ncrs severity check.',
   'tenant')
ON CONFLICT (code) DO NOTHING;

INSERT INTO industry_template_options (industry_type, category_code, code, label, sort_order)
SELECT i.industry_type, s.category_code, s.code, s.label, s.sort_order
FROM (VALUES
  ('INSPECTION_TYPE', 'incoming',            'Incoming',            1),
  ('INSPECTION_TYPE', 'in_process',          'In-process',          2),
  ('INSPECTION_TYPE', 'final',               'Final',               3),
  ('INSPECTION_TYPE', 'customer',            'Customer',            4),
  ('INSPECTION_TYPE', 'dispatch_readiness',  'Dispatch readiness',  5),
  ('DEFECT_SEVERITY', 'critical',            'Critical',            1),
  ('DEFECT_SEVERITY', 'major',               'Major',               2),
  ('DEFECT_SEVERITY', 'medium',              'Medium',              3),
  ('DEFECT_SEVERITY', 'minor',               'Minor',               4)
) AS s(category_code, code, label, sort_order)
CROSS JOIN (VALUES
  ('FABRICATION_STEEL'),
  ('PRECISION_MACHINING'),
  ('ELECTRONICS_ASSEMBLY'),
  ('FORGING'),
  ('CASTING'),
  ('GENERAL_MANUFACTURING')
) AS i(industry_type)
ON CONFLICT (industry_type, category_code, code) DO NOTHING;

INSERT INTO industry_template_options (industry_type, category_code, code, label, sort_order)
SELECT industry_type, 'DEFECT_CATEGORY', code, label, sort_order
FROM industry_template_options
WHERE category_code = 'REJECTION_REASONS'
ON CONFLICT (industry_type, category_code, code) DO NOTHING;

-- tenant_config_options forces row-level security, so set the tenant before each insert.
-- Optional session settings, for scripts/seed-quality-dropdowns.sh:
--   app.seed_tenant_id   uuid of one tenant
--   app.seed_tenant_slug slug of one tenant
-- Leave both empty to seed every tenant.
DO $$
DECLARE
  r RECORD;
  v_only UUID;
  v_slug TEXT;
  v_industry TEXT;
  v_only_setting TEXT := current_setting('app.seed_tenant_id', true);
  v_slug_setting TEXT := current_setting('app.seed_tenant_slug', true);
BEGIN
  v_only := CASE
    WHEN v_only_setting IS NULL OR btrim(v_only_setting) = '' THEN NULL
    ELSE v_only_setting::uuid
  END;
  v_slug := NULLIF(btrim(COALESCE(v_slug_setting, '')), '');

  FOR r IN
    SELECT id
    FROM tenant_registry
    WHERE (v_only IS NULL OR id = v_only)
      AND (v_slug IS NULL OR slug = v_slug)
  LOOP
    PERFORM set_config('app.current_tenant_id', r.id::text, true);

    SELECT industry_type INTO v_industry
    FROM tenant_onboarding_config
    WHERE tenant_id = r.id;

    IF v_industry IS NULL THEN
      v_industry := 'GENERAL_MANUFACTURING';
    END IF;

    INSERT INTO tenant_config_options
      (tenant_id, category_id, code, label, sort_order, is_system, is_active)
    SELECT r.id, cc.id, ito.code, ito.label, ito.sort_order, TRUE, TRUE
    FROM industry_template_options ito
    JOIN config_categories cc ON cc.code = ito.category_code
    WHERE ito.industry_type = v_industry
      AND ito.category_code IN ('INSPECTION_TYPE', 'DEFECT_CATEGORY', 'DEFECT_SEVERITY')
    ON CONFLICT (tenant_id, category_id, code) DO NOTHING;

    RAISE NOTICE 'Quality dropdowns seeded for tenant % (%)', r.id, v_industry;
  END LOOP;

  PERFORM set_config('app.current_tenant_id', '', true);
END $$;
