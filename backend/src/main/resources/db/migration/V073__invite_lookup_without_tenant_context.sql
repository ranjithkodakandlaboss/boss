-- Activation looks up an invite before a tenant context exists. FORCE ROW LEVEL
-- SECURITY hides every invite from the function owner, so the lookup sets a
-- request-scoped hash and a SELECT policy matches that one hash.

DROP POLICY IF EXISTS invite_tokens_hash_lookup ON invite_tokens;
CREATE POLICY invite_tokens_hash_lookup ON invite_tokens
  FOR SELECT
  USING (token_hash = current_setting('app.invite_token_hash', true));

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
  PERFORM set_config('app.invite_token_hash', p_token_hash, true);
  RETURN QUERY
  SELECT i.id, i.tenant_id, i.email, i.role, i.status, i.expires_at
  FROM invite_tokens i
  WHERE i.token_hash = p_token_hash;
END;
$$;

REVOKE ALL ON FUNCTION boss_lookup_invite_by_hash(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION boss_lookup_invite_by_hash(TEXT) TO boss_app_role;
GRANT EXECUTE ON FUNCTION boss_lookup_invite_by_hash(TEXT) TO boss_platform_admin_role;
