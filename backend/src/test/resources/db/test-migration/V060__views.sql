-- V060: Reporting views per physical-schema.md
CREATE OR REPLACE VIEW inventory_position_view AS
SELECT
  ml.tenant_id,
  ml.item_id,
  i.code AS item_code,
  i.name AS item_name,
  ml.store_id,
  s.name AS store_name,
  SUM(ml.quantity) AS total_qty,
  SUM(ml.reserved_qty) AS reserved_qty,
  SUM(ml.quantity - ml.reserved_qty) AS available_qty,
  u.symbol AS uom_symbol
FROM material_lots ml
JOIN items i ON ml.item_id = i.id
JOIN stores s ON ml.store_id = s.id
JOIN units_of_measure u ON i.base_uom_id = u.id
WHERE ml.status = 'available'
GROUP BY ml.tenant_id, ml.item_id, i.code, i.name, ml.store_id, s.name, u.symbol;

CREATE OR REPLACE VIEW invoice_ar_summary_view AS
SELECT
  i.tenant_id,
  i.customer_id,
  c.name AS customer_name,
  COUNT(*) AS invoice_count,
  SUM(i.total_amount) AS total_billed,
  SUM(i.paid_amount) AS total_received,
  SUM(i.total_amount - i.paid_amount) AS total_outstanding,
  MIN(i.due_date) FILTER (WHERE i.total_amount > i.paid_amount) AS oldest_due_date
FROM invoices i
JOIN customers c ON i.customer_id = c.id
WHERE i.status NOT IN ('cancelled')
GROUP BY i.tenant_id, i.customer_id, c.name;
