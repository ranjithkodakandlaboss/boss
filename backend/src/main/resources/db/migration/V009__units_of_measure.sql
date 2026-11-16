-- V009: Units of measure and conversions (tenant-scoped)
CREATE TABLE units_of_measure (
  id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant_registry(id),
  name      TEXT NOT NULL,
  symbol    TEXT NOT NULL,
  uom_type  TEXT NOT NULL DEFAULT 'quantity'
              CHECK (uom_type IN ('quantity','weight','volume','length','area','time','energy','other')),
  is_base   BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, symbol)
);

CREATE TABLE uom_conversions (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  from_uom_id UUID NOT NULL REFERENCES units_of_measure(id),
  to_uom_id   UUID NOT NULL REFERENCES units_of_measure(id),
  factor      NUMERIC(20,8) NOT NULL CHECK (factor > 0),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, from_uom_id, to_uom_id)
);

CREATE INDEX idx_uom_tenant            ON units_of_measure(tenant_id);
CREATE INDEX idx_uom_conversions_tenant ON uom_conversions(tenant_id);
