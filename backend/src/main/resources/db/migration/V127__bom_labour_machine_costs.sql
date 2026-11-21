ALTER TABLE bom_headers
    ADD COLUMN labour_cost_per_unit NUMERIC(15,4),
    ADD COLUMN machine_cost_per_unit NUMERIC(15,4);
