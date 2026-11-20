ALTER TABLE cpo_lines
    ADD COLUMN produced_qty NUMERIC(15,3) NOT NULL DEFAULT 0
        CHECK (produced_qty >= 0);

UPDATE cpo_lines l
SET produced_qty = COALESCE((
    SELECT SUM(j.quantity)
    FROM production_jobs j
    WHERE j.tenant_id = l.tenant_id
      AND j.cpo_line_id = l.id
      AND j.status = 'completed'
), 0);
