-- V034: Frame agreements and lines (tenant-scoped)
CREATE TABLE frame_agreements (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  fa_number        TEXT NOT NULL,
  customer_id      UUID NOT NULL REFERENCES customers(id),
  status           TEXT NOT NULL DEFAULT 'draft'
                     CHECK (status IN ('draft','active','expired','terminated')),
  valid_from       DATE NOT NULL,
  valid_to         DATE NOT NULL,
  payment_terms    INTEGER DEFAULT 30,
  notes            TEXT,
  created_by       UUID NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, fa_number)
);

CREATE TABLE frame_agreement_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  fa_id           UUID NOT NULL REFERENCES frame_agreements(id),
  item_id         UUID NOT NULL REFERENCES items(id),
  committed_qty   NUMERIC(15,3),
  unit_price      NUMERIC(15,2) NOT NULL,
  gst_rate        NUMERIC(5,2),
  line_no         INTEGER NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, fa_id, line_no)
);

CREATE INDEX idx_fa_tenant      ON frame_agreements(tenant_id, customer_id);
CREATE INDEX idx_fa_lines_fa    ON frame_agreement_lines(tenant_id, fa_id);
