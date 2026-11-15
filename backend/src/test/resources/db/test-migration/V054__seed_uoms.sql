DO $$
DECLARE
  grp_id UUID := '00000000-0000-0000-0000-000000000001';
  platform_id UUID := '00000000-0000-0000-0000-000000000001';
BEGIN
  INSERT INTO customer_groups (id, name, slug) VALUES (grp_id, 'Platform System Group', 'platform-sys') ON CONFLICT DO NOTHING;
  INSERT INTO tenant_registry (id, customer_group_id, name, slug, status) VALUES (platform_id, grp_id, 'Platform System Tenant', 'platform-sys-tenant', 'active') ON CONFLICT DO NOTHING;
END $$;
-- V054: Seed standard units of measure for the platform system tenant
-- These use a well-known platform tenant UUID (nil UUID = platform)
-- Tenant-specific UOMs are created by each tenant post-onboarding.

-- Standard international units (using nil UUID as platform sentinel)
-- NOTE: These are reference seeds; real tenants copy or reference these.
DO $$
DECLARE
  platform_id UUID := '00000000-0000-0000-0000-000000000001';
BEGIN
  -- Length
  INSERT INTO units_of_measure (id, tenant_id, symbol, name, uom_type, is_base)
  VALUES
    (gen_random_uuid(), platform_id, 'MM',  'Millimetre',  'length', FALSE),
    (gen_random_uuid(), platform_id, 'CM',  'Centimetre',  'length', FALSE),
    (gen_random_uuid(), platform_id, 'M',   'Metre',       'length', TRUE),
    (gen_random_uuid(), platform_id, 'KM',  'Kilometre',   'length', FALSE),
    (gen_random_uuid(), platform_id, 'IN',  'Inch',        'length', FALSE),
    (gen_random_uuid(), platform_id, 'FT',  'Foot',        'length', FALSE)
  ON CONFLICT DO NOTHING;

  -- Weight / Mass
  INSERT INTO units_of_measure (id, tenant_id, symbol, name, uom_type, is_base)
  VALUES
    (gen_random_uuid(), platform_id, 'MG',  'Milligram',   'weight', FALSE),
    (gen_random_uuid(), platform_id, 'G',   'Gram',        'weight', FALSE),
    (gen_random_uuid(), platform_id, 'KG',  'Kilogram',    'weight', TRUE),
    (gen_random_uuid(), platform_id, 'MT',  'Metric Tonne','weight', FALSE),
    (gen_random_uuid(), platform_id, 'LB',  'Pound',       'weight', FALSE)
  ON CONFLICT DO NOTHING;

  -- Volume
  INSERT INTO units_of_measure (id, tenant_id, symbol, name, uom_type, is_base)
  VALUES
    (gen_random_uuid(), platform_id, 'ML',  'Millilitre',  'volume', FALSE),
    (gen_random_uuid(), platform_id, 'L',   'Litre',       'volume', TRUE),
    (gen_random_uuid(), platform_id, 'CBM', 'Cubic Metre', 'volume', FALSE)
  ON CONFLICT DO NOTHING;

  -- Count / discrete
  INSERT INTO units_of_measure (id, tenant_id, symbol, name, uom_type, is_base)
  VALUES
    (gen_random_uuid(), platform_id, 'NOS', 'Numbers',     'quantity',  TRUE),
    (gen_random_uuid(), platform_id, 'PCS', 'Pieces',      'quantity',  FALSE),
    (gen_random_uuid(), platform_id, 'DZ',  'Dozen',       'quantity',  FALSE),
    (gen_random_uuid(), platform_id, 'SET', 'Set',         'quantity',  FALSE),
    (gen_random_uuid(), platform_id, 'PKT', 'Packet',      'quantity',  FALSE),
    (gen_random_uuid(), platform_id, 'BOX', 'Box',         'quantity',  FALSE),
    (gen_random_uuid(), platform_id, 'CTN', 'Carton',      'quantity',  FALSE),
    (gen_random_uuid(), platform_id, 'PAL', 'Pallet',      'quantity',  FALSE)
  ON CONFLICT DO NOTHING;

  -- Time (for routing/costing)
  INSERT INTO units_of_measure (id, tenant_id, symbol, name, uom_type, is_base)
  VALUES
    (gen_random_uuid(), platform_id, 'SEC', 'Second',      'time',   FALSE),
    (gen_random_uuid(), platform_id, 'MIN', 'Minute',      'time',   FALSE),
    (gen_random_uuid(), platform_id, 'HR',  'Hour',        'time',   TRUE),
    (gen_random_uuid(), platform_id, 'DAY', 'Day',         'time',   FALSE)
  ON CONFLICT DO NOTHING;

  -- Area
  INSERT INTO units_of_measure (id, tenant_id, symbol, name, uom_type, is_base)
  VALUES
    (gen_random_uuid(), platform_id, 'SQM', 'Square Metre','area',   TRUE),
    (gen_random_uuid(), platform_id, 'SQF', 'Square Feet', 'area',   FALSE)
  ON CONFLICT DO NOTHING;
END $$;
