-- V013: Suppliers, supplier contacts, supplier performance (tenant-scoped)
CREATE TABLE suppliers (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         UUID NOT NULL REFERENCES tenant_registry(id),
  code              TEXT NOT NULL,
  name              TEXT NOT NULL,
  legal_name        TEXT,
  gstin             TEXT CHECK (gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'),
  pan               TEXT,
  supplier_type     TEXT NOT NULL DEFAULT 'manufacturer'
                      CHECK (supplier_type IN ('manufacturer','trader','importer',
                                               'service_provider','other')),
  billing_address   JSONB,
  payment_terms     INTEGER DEFAULT 30,
  credit_limit      NUMERIC(15,2),
  performance_score NUMERIC(3,2),
  is_active         BOOLEAN NOT NULL DEFAULT TRUE,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, code)
);

CREATE TABLE supplier_contacts (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  supplier_id UUID NOT NULL REFERENCES suppliers(id),
  name        TEXT NOT NULL,
  role        TEXT,
  email       TEXT,
  phone       TEXT,
  is_primary  BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE supplier_performance_records (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         UUID NOT NULL REFERENCES tenant_registry(id),
  supplier_id       UUID NOT NULL REFERENCES suppliers(id),
  spo_id            UUID,
  grn_id            UUID,
  promised_date     DATE,
  actual_date       DATE,
  delay_days        INTEGER GENERATED ALWAYS AS (
                      CASE WHEN actual_date IS NOT NULL AND promised_date IS NOT NULL
                      THEN GREATEST(0, actual_date - promised_date) ELSE NULL END
                    ) STORED,
  quantity_ordered  NUMERIC(15,3),
  quantity_received NUMERIC(15,3),
  quantity_rejected NUMERIC(15,3),
  quality_pass      BOOLEAN,
  notes             TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_suppliers_tenant            ON suppliers(tenant_id);
CREATE INDEX idx_suppliers_code              ON suppliers(tenant_id, code);
CREATE INDEX idx_supplier_contacts           ON supplier_contacts(tenant_id, supplier_id);
CREATE INDEX idx_supplier_performance_tenant ON supplier_performance_records(tenant_id, supplier_id);
