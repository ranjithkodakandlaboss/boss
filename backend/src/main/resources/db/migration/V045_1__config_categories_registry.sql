-- V045_1: Config categories global registry (platform-wide, no RLS)
-- Was V045b in pre-v1.1.3 — renamed to valid Flyway format.
CREATE TABLE config_categories (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code        TEXT NOT NULL UNIQUE,
  name        TEXT NOT NULL,
  description TEXT,
  scope       TEXT NOT NULL DEFAULT 'tenant'
                CHECK (scope IN ('tenant','site','system')),
  is_active   BOOLEAN NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Seed standard categories
INSERT INTO config_categories (code, name, scope) VALUES
  ('ITEM_CATEGORY',       'Item Categories',               'tenant'),
  ('PRODUCTION_STAGES',   'Production Stage Types',        'tenant'),
  ('INSPECTION_TYPES',    'Quality Inspection Types',      'tenant'),
  ('REJECTION_REASONS',   'Rejection / NCR Reasons',       'tenant'),
  ('PAYMENT_MODES',       'Payment Modes',                 'tenant'),
  ('UNIT_TYPES',          'Unit of Measure Types',         'system'),
  ('STORE_TYPES',         'Store / Warehouse Types',       'system'),
  ('SUPPLIER_TYPES',      'Supplier Categories',           'tenant'),
  ('CUSTOMER_TYPES',      'Customer Types',                'tenant');
