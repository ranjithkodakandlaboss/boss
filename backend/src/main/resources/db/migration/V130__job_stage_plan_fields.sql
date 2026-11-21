-- V130: Store estimated duration directly on job stages so each job plan can retain or customize duration
ALTER TABLE job_stages
    ADD COLUMN IF NOT EXISTS estimated_duration_min INTEGER DEFAULT 0;

-- Populate existing job stages from routing template stages where available
UPDATE job_stages
SET estimated_duration_min = COALESCE(rts.estimated_duration_min, 0)
FROM production_jobs j
JOIN routing_template_stages rts
  ON rts.tenant_id = j.tenant_id
 AND rts.routing_id = j.routing_id
WHERE job_stages.job_id = j.id
  AND job_stages.seq_no = rts.seq_no
  AND (job_stages.estimated_duration_min IS NULL OR job_stages.estimated_duration_min = 0);
