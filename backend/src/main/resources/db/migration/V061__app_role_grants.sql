-- V061: Create boss_app role (non-superuser, respects RLS) for application connections.
-- The application connects as a user granted this role so that RLS policies are enforced.
-- Flyway runs as the provisioning superuser; boss_app is NEVER used for migrations.

DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'boss_app') THEN
    CREATE ROLE boss_app NOSUPERUSER NOBYPASSRLS NOLOGIN INHERIT;
  END IF;
END $$;

GRANT USAGE ON SCHEMA public TO boss_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO boss_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO boss_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO boss_app;

-- Default privileges: future tables/sequences/functions created by the migration user
-- also become accessible to boss_app.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO boss_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO boss_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT EXECUTE ON FUNCTIONS TO boss_app;
