-- V072: The first tenant owner has no app_users row to record as invited_by.
-- Later invites still store the inviting user. Lookup by token hash is
-- SECURITY DEFINER so activation can find the invite before a tenant context exists.
-- EXECUTE is revoked from PUBLIC and granted only to the customer and
-- platform-admin database roles.

ALTER TABLE invite_tokens ALTER COLUMN invited_by DROP NOT NULL;

CREATE OR REPLACE FUNCTION boss_lookup_invite_by_hash(p_token_hash TEXT)
RETURNS TABLE (
  out_invite_id UUID,
  out_tenant_id UUID,
  out_email TEXT,
  out_role TEXT,
  out_status TEXT,
  out_expires_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
STABLE
SET search_path = public
AS $$
BEGIN
  RETURN QUERY
  SELECT i.id, i.tenant_id, i.email, i.role, i.status, i.expires_at
  FROM invite_tokens i
  WHERE i.token_hash = p_token_hash;
END;
$$;

REVOKE ALL ON FUNCTION boss_lookup_invite_by_hash(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION boss_lookup_invite_by_hash(TEXT) TO boss_app_role;
GRANT EXECUTE ON FUNCTION boss_lookup_invite_by_hash(TEXT) TO boss_platform_admin_role;

CREATE OR REPLACE FUNCTION boss_create_owner_invite(
  p_tenant_id UUID,
  p_email TEXT,
  p_token_hash TEXT,
  p_expires_at TIMESTAMPTZ
) RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_id UUID;
BEGIN
  INSERT INTO invite_tokens(tenant_id, email, role, token_hash, status, expires_at)
  VALUES (p_tenant_id, p_email, 'OWNER', p_token_hash, 'pending', p_expires_at)
  RETURNING id INTO v_id;
  RETURN v_id;
END;
$$;

REVOKE ALL ON FUNCTION boss_create_owner_invite(UUID, TEXT, TEXT, TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION boss_create_owner_invite(UUID, TEXT, TEXT, TIMESTAMPTZ) TO boss_platform_admin_role;
