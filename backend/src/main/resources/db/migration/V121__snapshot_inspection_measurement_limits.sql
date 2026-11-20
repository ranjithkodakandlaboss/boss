ALTER TABLE inspection_checklist_responses
  ADD COLUMN unit_snapshot TEXT,
  ADD COLUMN spec_min_snapshot NUMERIC(15,4),
  ADD COLUMN spec_max_snapshot NUMERIC(15,4);
