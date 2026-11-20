ALTER TABLE supplier_quotation_lines
  ADD COLUMN IF NOT EXISTS grade TEXT;
