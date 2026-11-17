-- The Platform Admin service refuses to start until boss_admin can read and
-- update the customer-detail columns. This login is created outside Flyway.
-- Local and CI databases that have no boss_admin role skip the grants.

DO $boss_admin_customer_detail_grants$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'boss_admin') THEN
    GRANT INSERT ON customer_groups TO boss_admin;
    GRANT SELECT (id, name, slug) ON customer_groups TO boss_admin;

    GRANT SELECT, INSERT ON tenant_registry TO boss_admin;
    GRANT UPDATE (status, updated_at, initial_admin_email, name) ON tenant_registry TO boss_admin;

    GRANT INSERT ON legal_entities, physical_sites TO boss_admin;
    GRANT SELECT (id, tenant_id, name, legal_name, pan, entity_type, registered_address, status)
      ON legal_entities TO boss_admin;
    GRANT UPDATE (name, legal_name, registered_address, updated_at) ON legal_entities TO boss_admin;
    GRANT SELECT (id, tenant_id, legal_entity_id, gstin, trade_name, registration_type, state_code, status,
                  effective_from, effective_to) ON gst_registrations TO boss_admin;
    GRANT SELECT (id, tenant_id, legal_entity_id, name, code, site_type, address, status)
      ON physical_sites TO boss_admin;
    GRANT SELECT (gst_reg_id) ON physical_sites TO boss_admin;
    GRANT UPDATE (name, address, updated_at) ON physical_sites TO boss_admin;

    GRANT SELECT, INSERT ON tenant_onboarding_config TO boss_admin;
    GRANT UPDATE (industry_type, onboarding_completed_at) ON tenant_onboarding_config TO boss_admin;

    EXECUTE 'DROP POLICY IF EXISTS tenant_registry_platform_admin_read ON tenant_registry';
    EXECUTE 'CREATE POLICY tenant_registry_platform_admin_read ON tenant_registry '
            'FOR SELECT TO boss_admin USING (true)';
    EXECUTE 'DROP POLICY IF EXISTS tenant_onboarding_config_platform_admin_read ON tenant_onboarding_config';
    EXECUTE 'CREATE POLICY tenant_onboarding_config_platform_admin_read ON tenant_onboarding_config '
            'FOR SELECT TO boss_admin USING (true)';
  END IF;
END
$boss_admin_customer_detail_grants$;
