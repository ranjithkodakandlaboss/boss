-- V007: Legal entities and GST registrations (tenant-scoped)
CREATE TABLE legal_entities (
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          UUID NOT NULL REFERENCES tenant_registry(id),
  name               TEXT NOT NULL,
  legal_name         TEXT NOT NULL,
  pan                TEXT CHECK (pan ~ '^[A-Z]{5}[0-9]{4}[A-Z]$'),
  entity_type        TEXT CHECK (entity_type IN (
                       'PRIVATE_LIMITED','LLP','SOLE_PROPRIETORSHIP',
                       'PARTNERSHIP','PUBLIC_LIMITED','HUF','OTHER')),
  registered_address JSONB,
  status             TEXT NOT NULL DEFAULT 'active'
                       CHECK (status IN ('active','inactive','dissolved')),
  created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE gst_registrations (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           UUID NOT NULL REFERENCES tenant_registry(id),
  legal_entity_id     UUID NOT NULL REFERENCES legal_entities(id),
  gstin               TEXT NOT NULL,
  trade_name          TEXT,
  registration_type   TEXT NOT NULL DEFAULT 'regular'
                        CHECK (registration_type IN ('regular','composition','sez','casual','uin')),
  state_code          TEXT NOT NULL GENERATED ALWAYS AS (LEFT(gstin, 2)) STORED,
  status              TEXT NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active','cancelled','suspended')),
  effective_from      DATE NOT NULL,
  effective_to        DATE,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_gstin UNIQUE (tenant_id, gstin),
  CONSTRAINT chk_gstin_format CHECK (
    gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'
  )
);

CREATE INDEX idx_legal_entities_tenant ON legal_entities(tenant_id);
CREATE INDEX idx_gst_reg_tenant        ON gst_registrations(tenant_id);
CREATE INDEX idx_gst_reg_entity        ON gst_registrations(legal_entity_id);
