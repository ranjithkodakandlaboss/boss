-- Prototype commercial fields and owner-recorded quotation decisions.
-- Customer PO status stays on the physical state machine
-- (open, on_hold, partial, fulfilled, cancelled). The OpenAPI feasibility
-- statuses belong to issue #46 and are not stored here.

ALTER TABLE rfqs
  ADD COLUMN received_via TEXT,
  ADD COLUMN payment_terms INTEGER,
  ADD COLUMN delivery_terms TEXT;

ALTER TABLE rfq_lines
  ADD COLUMN drawing_number TEXT,
  ADD COLUMN drawing_revision TEXT,
  ADD COLUMN material TEXT,
  ADD COLUMN description TEXT,
  ADD COLUMN target_price NUMERIC(15,2),
  ADD COLUMN notes TEXT;

ALTER TABLE quotations
  ADD COLUMN discount_amount NUMERIC(15,2) NOT NULL DEFAULT 0,
  ADD COLUMN decision_channel TEXT,
  ADD COLUMN decision_note TEXT,
  ADD COLUMN decided_by UUID,
  ADD COLUMN decided_at TIMESTAMPTZ;

ALTER TABLE quotations
  ADD CONSTRAINT quotations_decision_channel_check
  CHECK (decision_channel IS NULL OR decision_channel = 'OWNER_RECORDED');

ALTER TABLE quotation_lines
  ADD COLUMN drawing_number TEXT,
  ADD COLUMN drawing_revision TEXT,
  ADD COLUMN description TEXT;

ALTER TABLE cpos
  ADD COLUMN discount_amount NUMERIC(15,2) NOT NULL DEFAULT 0,
  ADD COLUMN source_revision INTEGER;

CREATE UNIQUE INDEX uq_cpos_one_per_quotation
  ON cpos (tenant_id, quotation_id)
  WHERE quotation_id IS NOT NULL;
