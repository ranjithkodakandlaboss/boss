-- V114: Support direct Customer POs created without a prior quotation.
-- Adds direct_order_reason and delivery_terms to cpos,
-- and drawing_number / description directly to cpo_lines
-- (for direct orders where there is no linked quotation_line).

ALTER TABLE cpos
  ADD COLUMN direct_order_reason TEXT,
  ADD COLUMN delivery_terms       TEXT;

ALTER TABLE cpo_lines
  ADD COLUMN drawing_number TEXT,
  ADD COLUMN description     TEXT;
