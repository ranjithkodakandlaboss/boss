-- Stage-level plan columns (all nullable — additive; existing jobs keep NULL = no plan)
ALTER TABLE job_stages
    ADD COLUMN IF NOT EXISTS planned_start      DATE,
    ADD COLUMN IF NOT EXISTS planned_end        DATE,
    ADD COLUMN IF NOT EXISTS completed_qty      NUMERIC(15,3),
    ADD COLUMN IF NOT EXISTS blocked_qty        NUMERIC(15,3);

-- Standalone job support: allow production_jobs without a CPO
ALTER TABLE production_jobs
    ALTER COLUMN cpo_id      DROP NOT NULL,
    ALTER COLUMN cpo_line_id DROP NOT NULL;
