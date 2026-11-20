ALTER TABLE job_material_issues
    ADD COLUMN price_override_amount NUMERIC(15,4),
    ADD COLUMN price_override_reason TEXT,
    ADD COLUMN price_override_by UUID,
    ADD COLUMN price_override_at TIMESTAMPTZ;
