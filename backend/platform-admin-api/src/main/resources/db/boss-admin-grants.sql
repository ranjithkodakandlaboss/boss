-- Privileges for the boss-admin login used by boss-admin-api.
-- Apply as a superuser after:
--   CREATE ROLE boss_admin LOGIN PASSWORD '...' NOSUPERUSER NOBYPASSRLS INHERIT;
-- Do not grant boss_app_role or boss_platform_admin_role to this login.
-- Those roles can read or delete customer business data. This script does not.
-- This file is not a Flyway migration. Platform Core owns shared migrations.

GRANT USAGE ON SCHEMA public TO boss_admin;

GRANT INSERT ON customer_groups TO boss_admin;
GRANT SELECT (id) ON customer_groups TO boss_admin;

GRANT SELECT, INSERT ON tenant_registry TO boss_admin;
GRANT UPDATE (status, updated_at) ON tenant_registry TO boss_admin;

GRANT INSERT ON legal_entities, physical_sites TO boss_admin;
GRANT SELECT (id) ON legal_entities, physical_sites TO boss_admin;

GRANT EXECUTE ON FUNCTION boss_create_owner_invite(uuid, text, text, timestamptz) TO boss_admin;

GRANT SELECT, INSERT ON tenant_onboarding_config TO boss_admin;
GRANT UPDATE (industry_type, onboarding_completed_at) ON tenant_onboarding_config TO boss_admin;

GRANT SELECT, INSERT ON item_categories TO boss_admin;
GRANT EXECUTE ON FUNCTION seed_tenant_defaults(uuid, text) TO boss_admin;

GRANT SELECT ON platform_admin_users TO boss_admin;
GRANT SELECT, INSERT ON platform_audit_events TO boss_admin;

GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_keys TO boss_admin;

GRANT SELECT ON industry_template_options, config_categories TO boss_admin;
GRANT SELECT, INSERT ON tenant_config_options, units_of_measure TO boss_admin;
-- seed_tenant_defaults is SECURITY INVOKER. These grants let that shared function
-- write the industry rows inside the provisioning transaction. The admin service
-- does not insert those rows itself.

-- Cross-tenant reads are limited to the control-plane rows the list API returns.
-- Customer business tables stay closed by the absence of grants and by RLS.
DROP POLICY IF EXISTS tenant_registry_platform_admin_read ON tenant_registry;
CREATE POLICY tenant_registry_platform_admin_read ON tenant_registry
  FOR SELECT TO boss_admin
  USING (true);

DROP POLICY IF EXISTS tenant_onboarding_config_platform_admin_read ON tenant_onboarding_config;
CREATE POLICY tenant_onboarding_config_platform_admin_read ON tenant_onboarding_config
  FOR SELECT TO boss_admin
  USING (true);
