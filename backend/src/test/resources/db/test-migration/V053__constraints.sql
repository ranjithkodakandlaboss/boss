-- V053: Additional constraints missed in initial table creation

-- Positive quantity guards on inventory
ALTER TABLE stock_movements
  ADD CONSTRAINT chk_stock_movements_qty CHECK (quantity > 0);

ALTER TABLE material_lots
  ADD CONSTRAINT chk_material_lots_qty_nn CHECK (quantity >= 0);

ALTER TABLE stock_reservation_records
  ADD CONSTRAINT chk_stock_res_qty CHECK (reserved_qty > 0);

-- BOM: positive quantities
ALTER TABLE bom_lines
  ADD CONSTRAINT chk_bom_lines_qty CHECK (quantity > 0);

-- Invoice: amount consistency
ALTER TABLE invoices
  ADD CONSTRAINT chk_invoices_total_nn CHECK (total_amount >= 0);

ALTER TABLE invoice_lines
  ADD CONSTRAINT chk_invoice_lines_qty CHECK (quantity > 0),
  ADD CONSTRAINT chk_invoice_lines_price CHECK (unit_price >= 0);

-- Receipt: positive amount
ALTER TABLE receipts
  ADD CONSTRAINT chk_receipts_amount CHECK (amount > 0);

-- SPO lines: positive ordered qty
ALTER TABLE spo_lines
  ADD CONSTRAINT chk_spo_lines_qty CHECK (quantity > 0),
  ADD CONSTRAINT chk_spo_lines_price CHECK (unit_price >= 0);

-- GRN lines: positive received qty
ALTER TABLE grn_lines
  ADD CONSTRAINT chk_grn_lines_qty CHECK (received_qty > 0);

-- Job costing: non-negative amounts
ALTER TABLE job_costing_lines
  ADD CONSTRAINT chk_job_costing_lines_amount CHECK (amount >= 0);

-- Reorder policy: sensible thresholds
ALTER TABLE reorder_policies
  ADD CONSTRAINT chk_reorder_policy_levels CHECK (reorder_point >= 0 AND reorder_qty > 0);

-- Routing stage: non-negative times
ALTER TABLE routing_template_stages
  ADD CONSTRAINT chk_routing_stage_times CHECK (setup_time_min >= 0 AND run_time_min >= 0);

-- UOM conversion: positive factor
ALTER TABLE uom_conversions
  ADD CONSTRAINT chk_uom_conv_factor CHECK (factor > 0);
