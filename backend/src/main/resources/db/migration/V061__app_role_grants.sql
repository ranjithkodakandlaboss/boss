-- V061: Application privilege roles (non-superuser, non-BYPASSRLS).
--
-- Design:
--   boss_app_role            — NOLOGIN privilege role for the customer application
--   boss_platform_admin_role — NOLOGIN privilege role for Platform Admin (restricted)
--
-- The actual login accounts (boss_app, boss_platform_admin) are Cloud SQL users
-- created by deployment scripts, NOT by migrations.  After deployment, the DBA runs:
--   GRANT boss_app_role            TO boss_app;
--   GRANT boss_platform_admin_role TO boss_platform_admin;
--
-- Never use a superuser or BYPASSRLS role for application connections.

-- ---- Customer application role ----
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'boss_app_role') THEN
    CREATE ROLE boss_app_role NOSUPERUSER NOBYPASSRLS NOLOGIN INHERIT;
  END IF;
END $$;

GRANT USAGE ON SCHEMA public TO boss_app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO boss_app_role;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO boss_app_role;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO boss_app_role;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO boss_app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO boss_app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT EXECUTE ON FUNCTIONS TO boss_app_role;

-- ---- Platform Admin role (restricted) ----
-- Must NOT have access to customer business tables and must NOT BYPASSRLS.
-- Only platform-infrastructure tables are granted.
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'boss_platform_admin_role') THEN
    CREATE ROLE boss_platform_admin_role NOSUPERUSER NOBYPASSRLS NOLOGIN INHERIT;
  END IF;
END $$;

GRANT USAGE ON SCHEMA public TO boss_platform_admin_role;

-- Platform admin may read/write platform-scoped tables only
GRANT SELECT, INSERT, UPDATE, DELETE ON customer_groups          TO boss_platform_admin_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_registry          TO boss_platform_admin_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_entitlements      TO boss_platform_admin_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON platform_admin_users     TO boss_platform_admin_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON platform_audit_events    TO boss_platform_admin_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON outbox_events            TO boss_platform_admin_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_keys         TO boss_platform_admin_role;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO boss_platform_admin_role;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO boss_platform_admin_role;
