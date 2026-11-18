ALTER TABLE stock_reservation_records
  ADD COLUMN cpo_line_id UUID REFERENCES cpo_lines(id);

ALTER TABLE stock_reservation_records
  ADD CONSTRAINT chk_stock_reservation_cpo_line
  CHECK (cpo_line_id IS NULL OR reference_type = 'cpo');

CREATE INDEX idx_stock_reservations_cpo_line
  ON stock_reservation_records(tenant_id, cpo_line_id)
  WHERE status = 'active' AND cpo_line_id IS NOT NULL;
