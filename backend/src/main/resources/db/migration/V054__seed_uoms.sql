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
  INSERT INTO units_of_measure (id, tenant_id, code, name, uom_type, is_base_unit)
  VALUES
    (gen_random_uuid(), platform_id, 'MM',  'Millimetre',  'length', FALSE),
    (gen_random_uuid(), platform_id, 'CM',  'Centimetre',  'length', FALSE),
    (gen_random_uuid(), platform_id, 'M',   'Metre',       'length', TRUE),
    (gen_random_uuid(), platform_id, 'KM',  'Kilometre',   'length', FALSE),
    (gen_random_uuid(), platform_id, 'IN',  'Inch',        'length', FALSE),
    (gen_random_uuid(), platform_id, 'FT',  'Foot',        'length', FALSE)
  ON CONFLICT DO NOTHING;

  -- Weight / Mass
  INSERT INTO units_of_measure (id, tenant_id, code, name, uom_type, is_base_unit)
  VALUES
    (gen_random_uuid(), platform_id, 'MG',  'Milligram',   'weight', FALSE),
    (gen_random_uuid(), platform_id, 'G',   'Gram',        'weight', FALSE),
    (gen_random_uuid(), platform_id, 'KG',  'Kilogram',    'weight', TRUE),
    (gen_random_uuid(), platform_id, 'MT',  'Metric Tonne','weight', FALSE),
    (gen_random_uuid(), platform_id, 'LB',  'Pound',       'weight', FALSE)
  ON CONFLICT DO NOTHING;

  -- Volume
  INSERT INTO units_of_measure (id, tenant_id, code, name, uom_type, is_base_unit)
  VALUES
    (gen_random_uuid(), platform_id, 'ML',  'Millilitre',  'volume', FALSE),
    (gen_random_uuid(), platform_id, 'L',   'Litre',       'volume', TRUE),
    (gen_random_uuid(), platform_id, 'CBM', 'Cubic Metre', 'volume', FALSE)
  ON CONFLICT DO NOTHING;

  -- Count / discrete
  INSERT INTO units_of_measure (id, tenant_id, code, name, uom_type, is_base_unit)
  VALUES
    (gen_random_uuid(), platform_id, 'NOS', 'Numbers',     'count',  TRUE),
    (gen_random_uuid(), platform_id, 'PCS', 'Pieces',      'count',  FALSE),
    (gen_random_uuid(), platform_id, 'DZ',  'Dozen',       'count',  FALSE),
    (gen_random_uuid(), platform_id, 'SET', 'Set',         'count',  FALSE),
    (gen_random_uuid(), platform_id, 'PKT', 'Packet',      'count',  FALSE),
    (gen_random_uuid(), platform_id, 'BOX', 'Box',         'count',  FALSE),
    (gen_random_uuid(), platform_id, 'CTN', 'Carton',      'count',  FALSE),
    (gen_random_uuid(), platform_id, 'PAL', 'Pallet',      'count',  FALSE)
  ON CONFLICT DO NOTHING;

  -- Time (for routing/costing)
  INSERT INTO units_of_measure (id, tenant_id, code, name, uom_type, is_base_unit)
  VALUES
    (gen_random_uuid(), platform_id, 'SEC', 'Second',      'time',   FALSE),
    (gen_random_uuid(), platform_id, 'MIN', 'Minute',      'time',   FALSE),
    (gen_random_uuid(), platform_id, 'HR',  'Hour',        'time',   TRUE),
    (gen_random_uuid(), platform_id, 'DAY', 'Day',         'time',   FALSE)
  ON CONFLICT DO NOTHING;

  -- Area
  INSERT INTO units_of_measure (id, tenant_id, code, name, uom_type, is_base_unit)
  VALUES
    (gen_random_uuid(), platform_id, 'SQM', 'Square Metre','area',   TRUE),
    (gen_random_uuid(), platform_id, 'SQF', 'Square Feet', 'area',   FALSE)
  ON CONFLICT DO NOTHING;
END $$;
