-- V053: Additional CHECK constraints for data integrity.
-- Only adds constraints NOT already defined inline in table DDL.

-- Positive quantity on stock movements
ALTER TABLE stock_movements
  ADD CONSTRAINT chk_stock_movements_qty CHECK (quantity > 0);

-- Invoice total non-negative
ALTER TABLE invoices
  ADD CONSTRAINT chk_invoices_total_nn CHECK (total_amount >= 0);

-- Invoice line unit price non-negative
ALTER TABLE invoice_lines
  ADD CONSTRAINT chk_invoice_lines_price CHECK (unit_price >= 0);

-- SPO line unit price non-negative (quantity already has inline CHECK)
ALTER TABLE spo_lines
  ADD CONSTRAINT chk_spo_lines_price CHECK (unit_price >= 0);

-- Reorder policy: sensible thresholds
ALTER TABLE reorder_policies
  ADD CONSTRAINT chk_reorder_policy_levels CHECK (reorder_point >= 0 AND reorder_qty > 0);

-- Routing stage: non-negative times
ALTER TABLE routing_template_stages
  ADD CONSTRAINT chk_routing_stage_times CHECK (setup_time_min >= 0 AND run_time_min >= 0);
