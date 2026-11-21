-- V129: Other manufacturing cost on BOM header, and unit cost on BOM lines
ALTER TABLE bom_headers
    ADD COLUMN IF NOT EXISTS other_cost_per_unit NUMERIC(15,4);

ALTER TABLE bom_lines
    ADD COLUMN IF NOT EXISTS unit_cost NUMERIC(15,4);
