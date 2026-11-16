ALTER TABLE rfqs
  ADD COLUMN rfq_type_id UUID,
  ADD COLUMN rfq_type_label TEXT;

ALTER TABLE quotations
  ADD COLUMN estimated_material_cost NUMERIC(15,2);

ALTER TABLE quotation_lines
  ADD COLUMN estimated_material_cost_per_unit NUMERIC(15,2);

ALTER TABLE quotation_revisions
  ADD CONSTRAINT uq_quotation_revisions_source_revision
  UNIQUE (tenant_id, quotation_id, revision);

ALTER TABLE quotations
  DROP CONSTRAINT IF EXISTS quotations_status_check;

ALTER TABLE quotations
  ADD CONSTRAINT quotations_status_check
  CHECK (status IN ('draft','sent','negotiating','accepted','rejected','expired','superseded'));

CREATE TABLE quotation_negotiation_history (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           UUID NOT NULL REFERENCES tenant_registry(id),
  quotation_id        UUID NOT NULL REFERENCES quotations(id),
  round               INTEGER NOT NULL CHECK (round > 0),
  party_type          TEXT NOT NULL CHECK (party_type IN ('TENANT','CUSTOMER')),
  offered_unit_prices JSONB NOT NULL,
  note                TEXT,
  material_cost_at_time NUMERIC(15,2),
  created_by          UUID NOT NULL,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, quotation_id, round, party_type)
);

CREATE INDEX idx_quotation_negotiation_history
  ON quotation_negotiation_history(tenant_id, quotation_id, round, created_at);

ALTER TABLE quotation_negotiation_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY quotation_negotiation_history_isolation
  ON quotation_negotiation_history USING (tenant_id = boss_current_tenant_id());

CREATE OR REPLACE FUNCTION prevent_quotation_negotiation_history_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'quotation negotiation history is append-only';
END;
$$;

CREATE TRIGGER quotation_negotiation_history_append_only
  BEFORE UPDATE OR DELETE ON quotation_negotiation_history
  FOR EACH ROW EXECUTE FUNCTION prevent_quotation_negotiation_history_mutation();
