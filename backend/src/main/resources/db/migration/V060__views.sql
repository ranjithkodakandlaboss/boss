-- V060: Reporting views — inventory_position_view and invoice_summary_view
-- Views use RLS on base tables; no additional security needed.

CREATE OR REPLACE VIEW inventory_position_view AS
SELECT
  ml.tenant_id,
  ml.item_id,
  i.item_code,
  i.name            AS item_name,
  ml.store_id,
  s.name            AS store_name,
  ml.id             AS lot_id,
  ml.lot_number,
  ml.quantity_on_hand,
  ml.unit_cost,
  ml.quantity_on_hand * COALESCE(ml.unit_cost, 0) AS lot_value,
  ml.expiry_date,
  ml.status         AS lot_status,
  COALESCE(res.reserved_qty, 0)                   AS reserved_qty,
  ml.quantity_on_hand - COALESCE(res.reserved_qty, 0) AS available_qty
FROM material_lots ml
JOIN items i  ON i.id = ml.item_id
JOIN stores s ON s.id = ml.store_id
LEFT JOIN (
  SELECT lot_id, SUM(reserved_qty) AS reserved_qty
  FROM   stock_reservation_records
  WHERE  status = 'active'
  GROUP BY lot_id
) res ON res.lot_id = ml.id
WHERE ml.status = 'active';

COMMENT ON VIEW inventory_position_view IS
  'Live inventory position per lot with reservations subtracted. '
  'Filtered by RLS on material_lots (tenant-scoped).';


CREATE OR REPLACE VIEW invoice_summary_view AS
SELECT
  inv.tenant_id,
  inv.id                                          AS invoice_id,
  inv.invoice_number,
  inv.invoice_date,
  inv.due_date,
  inv.status,
  inv.customer_id,
  c.name                                          AS customer_name,
  inv.legal_entity_id,
  le.name                                         AS legal_entity_name,
  inv.total_amount,
  inv.tax_amount,
  inv.total_amount - inv.tax_amount               AS taxable_amount,
  COALESCE(paid.paid_amount, 0)                   AS paid_amount,
  inv.total_amount - COALESCE(paid.paid_amount, 0) AS outstanding_amount,
  CASE
    WHEN inv.status = 'cancelled' THEN 'cancelled'
    WHEN inv.due_date < CURRENT_DATE AND inv.total_amount > COALESCE(paid.paid_amount, 0) THEN 'overdue'
    WHEN inv.total_amount <= COALESCE(paid.paid_amount, 0) THEN 'paid'
    ELSE 'open'
  END                                             AS payment_status,
  inv.created_at
FROM invoices inv
JOIN customers c      ON c.id  = inv.customer_id
JOIN legal_entities le ON le.id = inv.legal_entity_id
LEFT JOIN (
  SELECT invoice_id, SUM(amount) AS paid_amount
  FROM   receipt_allocations
  GROUP BY invoice_id
) paid ON paid.invoice_id = inv.id;

COMMENT ON VIEW invoice_summary_view IS
  'Invoice summary with payment status and outstanding amount. '
  'Filtered by RLS on invoices (tenant-scoped).';
