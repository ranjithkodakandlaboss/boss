-- V079: Link a purchase requisition to the Customer PO that created it.
-- Inventory is not integrated, so a user-confirmed material shortage records the
-- originating Customer PO on the requisition and refuses a second open requisition.

ALTER TABLE purchase_requisitions
  DROP CONSTRAINT IF EXISTS purchase_requisitions_origin_type_check;

ALTER TABLE purchase_requisitions
  ADD CONSTRAINT purchase_requisitions_origin_type_check
  CHECK (origin_type IN ('manual', 'production_job', 'stock_driven', 'customer_po'));

ALTER TABLE purchase_requisitions
  DROP CONSTRAINT IF EXISTS purchase_requisitions_reference_type_check;

ALTER TABLE purchase_requisitions
  ADD CONSTRAINT purchase_requisitions_reference_type_check
  CHECK (reference_type IS NULL OR reference_type IN (
    'production_job', 'manual', 'reorder', 'stock_driven', 'customer_po'
  ));

CREATE UNIQUE INDEX IF NOT EXISTS uq_prs_open_customer_po
  ON purchase_requisitions (tenant_id, reference_id)
  WHERE origin_type = 'customer_po'
    AND status NOT IN ('cancelled', 'rejected');
