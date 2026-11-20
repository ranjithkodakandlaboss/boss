-- Estimated run time in minutes per stage, used for backward scheduling plan preview
ALTER TABLE routing_template_stages
    ADD COLUMN IF NOT EXISTS estimated_duration_min INTEGER NOT NULL DEFAULT 0;

-- Back-fill from existing run_time_min + setup_time_min
UPDATE routing_template_stages
SET estimated_duration_min = COALESCE(run_time_min, 0) + COALESCE(setup_time_min, 0)
WHERE estimated_duration_min = 0 AND (run_time_min > 0 OR setup_time_min > 0);
