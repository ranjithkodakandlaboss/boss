ALTER TABLE bom_lines
  ADD COLUMN quantity_formula TEXT
    CHECK (quantity_formula IS NULL OR (btrim(quantity_formula) <> '' AND char_length(quantity_formula) <= 256));

ALTER TABLE rfq_lines
  ADD COLUMN specification_values JSONB NOT NULL DEFAULT '{}'::jsonb
    CHECK (jsonb_typeof(specification_values) = 'object');

ALTER TABLE quotation_lines
  ADD COLUMN specification_values JSONB NOT NULL DEFAULT '{}'::jsonb
    CHECK (jsonb_typeof(specification_values) = 'object');
