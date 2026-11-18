-- Align ncrs and concessions tables with quality.yaml contract.
-- defect_type (free-text) → defect_category_id (FK to config_options)
-- concession_type removed; justification stored in approval_notes

ALTER TABLE ncrs
    ADD COLUMN IF NOT EXISTS defect_category_id UUID REFERENCES config_options(id);

-- Migrate existing free-text defect_type values: leave defect_category_id NULL
-- (existing rows pre-date config-driven categories)

ALTER TABLE ncrs
    DROP COLUMN IF EXISTS defect_type;

-- concession_type column is no longer part of the contract; drop it
ALTER TABLE concessions
    DROP COLUMN IF EXISTS concession_type;
