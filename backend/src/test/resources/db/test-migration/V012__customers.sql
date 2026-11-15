-- V012: Customers and customer contacts (tenant-scoped)
CREATE TABLE customers (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  code             TEXT NOT NULL,
  name             TEXT NOT NULL,
  legal_name       TEXT,
  gstin            TEXT CHECK (gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'),
  pan              TEXT,
  customer_type    TEXT NOT NULL DEFAULT 'business'
                     CHECK (customer_type IN ('business','individual')),
  billing_address  JSONB,
  shipping_address JSONB,
  credit_limit     NUMERIC(15,2),
  credit_days      INTEGER DEFAULT 30,
  is_active        BOOLEAN NOT NULL DEFAULT TRUE,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, code)
);

CREATE TABLE customer_contacts (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  customer_id UUID NOT NULL REFERENCES customers(id),
  name        TEXT NOT NULL,
  role        TEXT,
  email       TEXT,
  phone       TEXT,
  is_primary  BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_customers_tenant     ON customers(tenant_id);
CREATE INDEX idx_customers_code       ON customers(tenant_id, code);
CREATE INDEX idx_customer_contacts    ON customer_contacts(tenant_id, customer_id);
