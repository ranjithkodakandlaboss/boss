-- V064: Bootstrap function for Firebase UID → tenant context resolution.
--
-- Problem: app_users is RLS-protected (tenant_id = boss_current_tenant_id()).
-- When a request arrives, the application has no tenant context yet — it must
-- query app_users to discover the tenant, but RLS blocks the query.
--
-- Solution: a SECURITY DEFINER function owned by the migration user (superuser).
-- The function runs with the owner's privileges, bypassing RLS for this single,
-- tightly-scoped lookup.  It returns ONLY the row for the given Firebase UID —
-- it cannot enumerate other users or tenants.
--
-- Security properties:
--   - Input: Firebase UID (externally validated by Firebase Auth SDK before this call)
--   - Output: exactly the requesting user's data
--   - search_path is pinned to prevent injection via schema search
--   - Granted only to boss_app_role and boss_platform_admin_role (not PUBLIC)

CREATE OR REPLACE FUNCTION boss_resolve_firebase_uid(p_firebase_uid TEXT)
RETURNS TABLE (
  out_user_id    UUID,
  out_tenant_id  UUID,
  out_status     TEXT,
  out_role       TEXT,
  out_site_id    UUID
)
LANGUAGE plpgsql
SECURITY DEFINER
STABLE
SET search_path = public
AS $$
BEGIN
  RETURN QUERY
  SELECT
    u.id              AS out_user_id,
    u.tenant_id       AS out_tenant_id,
    u.status          AS out_status,
    r.role::TEXT      AS out_role,
    r.site_id         AS out_site_id
  FROM app_users u
  LEFT JOIN user_roles r
    ON r.user_id = u.id
   AND r.tenant_id = u.tenant_id
  WHERE u.firebase_uid = p_firebase_uid;
END;
$$;

GRANT EXECUTE ON FUNCTION boss_resolve_firebase_uid(TEXT) TO boss_app_role;
GRANT EXECUTE ON FUNCTION boss_resolve_firebase_uid(TEXT) TO boss_platform_admin_role;

-- ============================================================
-- Deployment note (NOT enforced here — see deployment scripts):
--   GRANT boss_app_role            TO boss_app;
--   GRANT boss_platform_admin_role TO boss_platform_admin;
-- These logins are Cloud SQL accounts, not created by migrations.
-- ============================================================
