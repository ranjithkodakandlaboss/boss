-- Sign-in resolves a Firebase UID before a tenant context exists. The lookup
-- function is owned by boss_app, which does not bypass FORCE ROW LEVEL SECURITY.
-- Match the one UID for this request, then set the tenant context so the
-- caller's roles are visible.

DROP POLICY IF EXISTS app_users_firebase_uid_lookup ON app_users;
CREATE POLICY app_users_firebase_uid_lookup ON app_users
  FOR SELECT
  USING (firebase_uid = current_setting('app.firebase_uid', true));

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
DECLARE
  v_tenant_id UUID;
BEGIN
  PERFORM set_config('app.firebase_uid', p_firebase_uid, true);
  SELECT u.tenant_id INTO v_tenant_id
  FROM app_users u
  WHERE u.firebase_uid = p_firebase_uid;
  IF v_tenant_id IS NOT NULL THEN
    PERFORM set_config('app.current_tenant_id', v_tenant_id::text, true);
  END IF;
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

REVOKE ALL ON FUNCTION boss_resolve_firebase_uid(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION boss_resolve_firebase_uid(TEXT) TO boss_app_role;
GRANT EXECUTE ON FUNCTION boss_resolve_firebase_uid(TEXT) TO boss_platform_admin_role;
