-- V117: Backfill km (Kilometres) meter type for tenants that existed before V089 seeded it
DO $$
DECLARE r RECORD;
BEGIN
  FOR r IN SELECT id FROM tenant_registry LOOP
    PERFORM set_config('app.current_tenant_id', r.id::text, true);
    INSERT INTO tenant_config_options (tenant_id, category_id, code, label, sort_order, is_system, is_active)
    SELECT r.id, cc.id, 'km', 'Kilometres', 4, TRUE, TRUE
    FROM config_categories cc
    WHERE cc.code = 'METER_TYPE'
    ON CONFLICT (tenant_id, category_id, code) DO NOTHING;
  END LOOP;
  PERFORM set_config('app.current_tenant_id', '', true);
END $$;
