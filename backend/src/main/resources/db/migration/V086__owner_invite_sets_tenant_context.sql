-- V079 replaced boss_create_owner_invite without setting the tenant context.
-- invite_tokens forces row-level security, and the function owner does not
-- bypass it. Platform Admin calls this function after its own transaction has
-- ended, so the session tenant id is gone and the insert is rejected.

CREATE OR REPLACE FUNCTION boss_create_owner_invite(
    p_tenant_id UUID,
    p_email TEXT,
    p_token_hash TEXT,
    p_expires_at TIMESTAMPTZ
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_invite_id UUID;
BEGIN
    PERFORM set_config('app.current_tenant_id', p_tenant_id::text, true);

    IF NOT EXISTS (SELECT 1 FROM tenant_registry WHERE id = p_tenant_id) THEN
        RAISE EXCEPTION 'Tenant % does not exist', p_tenant_id;
    END IF;

    UPDATE invite_tokens
    SET status = 'cancelled'
    WHERE tenant_id = p_tenant_id
      AND role = 'OWNER'
      AND status = 'pending';

    INSERT INTO invite_tokens (
        tenant_id, email, role, token_hash, invited_by, status, expires_at, created_at
    ) VALUES (
        p_tenant_id, p_email, 'OWNER', p_token_hash, NULL, 'pending', p_expires_at, NOW()
    )
    RETURNING id INTO v_invite_id;

    RETURN v_invite_id;
END;
$$;

REVOKE ALL ON FUNCTION boss_create_owner_invite(UUID, TEXT, TEXT, TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION boss_create_owner_invite(UUID, TEXT, TEXT, TIMESTAMPTZ) TO boss_platform_admin_role;

DO $boss_admin_owner_invite_grant$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'boss_admin') THEN
    GRANT EXECUTE ON FUNCTION boss_create_owner_invite(UUID, TEXT, TEXT, TIMESTAMPTZ) TO boss_admin;
  END IF;
END
$boss_admin_owner_invite_grant$;
