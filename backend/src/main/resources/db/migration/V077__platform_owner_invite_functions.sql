-- V077: Platform owner invite functions used by Platform Admin.
-- V070 is already applied as purchase_requisition_lifecycle. These functions
-- were created on the DEV database outside Flyway and must exist for a fresh
-- database. This migration does not edit any applied script.
-- EXECUTE is revoked from PUBLIC. boss_admin receives EXECUTE from
-- db/boss-admin-grants.sql after that login exists.

-- 1. Make invited_by nullable so platform-initiated initial owner invites do not require a prior app_user
ALTER TABLE invite_tokens ALTER COLUMN invited_by DROP NOT NULL;

-- 2. Stored function: boss_create_owner_invite
-- Callable by boss_admin / boss_platform_admin_role to create an initial OWNER invite
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
    IF NOT EXISTS (SELECT 1 FROM tenant_registry WHERE id = p_tenant_id) THEN
        RAISE EXCEPTION 'Tenant % does not exist', p_tenant_id;
    END IF;

    -- Cancel any existing pending invites for this email and tenant
    UPDATE invite_tokens
    SET status = 'cancelled'
    WHERE tenant_id = p_tenant_id AND email = p_email AND status = 'pending';

    INSERT INTO invite_tokens (
        tenant_id,
        email,
        role,
        token_hash,
        invited_by,
        status,
        expires_at,
        created_at
    ) VALUES (
        p_tenant_id,
        p_email,
        'OWNER',
        p_token_hash,
        NULL,
        'pending',
        p_expires_at,
        NOW()
    )
    RETURNING id INTO v_invite_id;

    RETURN v_invite_id;
END;
$$;

REVOKE ALL ON FUNCTION boss_create_owner_invite(UUID, TEXT, TEXT, TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION boss_create_owner_invite(UUID, TEXT, TEXT, TIMESTAMPTZ) TO boss_platform_admin_role;

-- 3. Stored function: boss_validate_invite_token
-- Validates invite token without requiring authentication (for customer activation landing screen)
CREATE OR REPLACE FUNCTION boss_validate_invite_token(p_token_hash TEXT)
RETURNS TABLE (
    out_invite_id UUID,
    out_tenant_id UUID,
    out_tenant_name TEXT,
    out_tenant_slug TEXT,
    out_email TEXT,
    out_role TEXT,
    out_status TEXT,
    out_expires_at TIMESTAMPTZ,
    out_is_expired BOOLEAN
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT
        i.id AS out_invite_id,
        i.tenant_id AS out_tenant_id,
        t.name AS out_tenant_name,
        t.slug AS out_tenant_slug,
        i.email AS out_email,
        i.role AS out_role,
        i.status AS out_status,
        i.expires_at AS out_expires_at,
        (i.expires_at < NOW()) AS out_is_expired
    FROM invite_tokens i
    JOIN tenant_registry t ON t.id = i.tenant_id
    WHERE i.token_hash = p_token_hash;
END;
$$;

REVOKE ALL ON FUNCTION boss_validate_invite_token(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION boss_validate_invite_token(TEXT) TO boss_platform_admin_role;

-- 4. Stored function: boss_accept_owner_invite
-- Atomically creates customer app_user, assigns OWNER role, updates invite status, and activates tenant
CREATE OR REPLACE FUNCTION boss_accept_owner_invite(
    p_token_hash TEXT,
    p_firebase_uid TEXT,
    p_display_name TEXT,
    p_phone TEXT
)
RETURNS TABLE (
    out_user_id UUID,
    out_tenant_id UUID,
    out_tenant_slug TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_invite RECORD;
    v_user_id UUID;
    v_tenant_slug TEXT;
BEGIN
    SELECT i.id, i.tenant_id, i.email, i.status, i.expires_at, t.slug
    INTO v_invite
    FROM invite_tokens i
    JOIN tenant_registry t ON t.id = i.tenant_id
    WHERE i.token_hash = p_token_hash
    FOR UPDATE OF i;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Invalid invitation token';
    END IF;

    IF v_invite.status != 'pending' THEN
        RAISE EXCEPTION 'Invitation token has already been %', v_invite.status;
    END IF;

    IF v_invite.expires_at < NOW() THEN
        UPDATE invite_tokens SET status = 'expired' WHERE id = v_invite.id;
        RAISE EXCEPTION 'Invitation token has expired';
    END IF;

    -- Upsert app_users record
    INSERT INTO app_users (
        tenant_id,
        firebase_uid,
        email,
        display_name,
        phone_wa,
        status,
        created_at,
        updated_at
    ) VALUES (
        v_invite.tenant_id,
        p_firebase_uid,
        v_invite.email,
        COALESCE(p_display_name, 'Administrator'),
        p_phone,
        'active',
        NOW(),
        NOW()
    )
    ON CONFLICT (tenant_id, email) DO UPDATE
    SET firebase_uid = EXCLUDED.firebase_uid,
        display_name = COALESCE(EXCLUDED.display_name, app_users.display_name),
        phone_wa = COALESCE(EXCLUDED.phone_wa, app_users.phone_wa),
        status = 'active',
        updated_at = NOW()
    RETURNING id INTO v_user_id;

    -- Assign OWNER role
    INSERT INTO user_roles (
        tenant_id,
        user_id,
        role,
        site_id,
        granted_at
    ) VALUES (
        v_invite.tenant_id,
        v_user_id,
        'OWNER',
        NULL,
        NOW()
    )
    ON CONFLICT (tenant_id, user_id, role, site_id) DO NOTHING;

    -- Mark invite accepted
    UPDATE invite_tokens
    SET status = 'accepted'
    WHERE id = v_invite.id;

    -- Transition tenant to active if still provisioning
    UPDATE tenant_registry
    SET status = 'active',
        updated_at = NOW()
    WHERE id = v_invite.tenant_id
      AND status = 'provisioning';

    RETURN QUERY
    SELECT v_user_id, v_invite.tenant_id, v_invite.slug;
END;
$$;

REVOKE ALL ON FUNCTION boss_accept_owner_invite(TEXT, TEXT, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION boss_accept_owner_invite(TEXT, TEXT, TEXT, TEXT) TO boss_platform_admin_role;

-- 5. Stored function: boss_get_tenant_invitation_summary
-- Retrieves invitation state for platform admin tenant lifecycle visibility
CREATE OR REPLACE FUNCTION boss_get_tenant_invitation_summary(p_tenant_id UUID)
RETURNS TABLE (
    out_invite_id UUID,
    out_email TEXT,
    out_role TEXT,
    out_status TEXT,
    out_created_at TIMESTAMPTZ,
    out_expires_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT
        i.id AS out_invite_id,
        i.email AS out_email,
        i.role AS out_role,
        i.status AS out_status,
        i.created_at AS out_created_at,
        i.expires_at AS out_expires_at
    FROM invite_tokens i
    WHERE i.tenant_id = p_tenant_id
    ORDER BY i.created_at DESC
    LIMIT 1;
END;
$$;

REVOKE ALL ON FUNCTION boss_get_tenant_invitation_summary(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION boss_get_tenant_invitation_summary(UUID) TO boss_platform_admin_role;

-- 6. Stored function: boss_register_gstin
-- Allows recording initial GSTIN during tenant provisioning without granting broad table access
CREATE OR REPLACE FUNCTION boss_register_gstin(
    p_tenant_id UUID,
    p_legal_entity_id UUID,
    p_gstin TEXT,
    p_trade_name TEXT
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_id UUID;
BEGIN
    INSERT INTO gst_registrations (
        tenant_id,
        legal_entity_id,
        gstin,
        trade_name,
        registration_type,
        effective_from,
        status,
        created_at,
        updated_at
    ) VALUES (
        p_tenant_id,
        p_legal_entity_id,
        UPPER(TRIM(p_gstin)),
        p_trade_name,
        'regular',
        CURRENT_DATE,
        'active',
        NOW(),
        NOW()
    )
    ON CONFLICT (tenant_id, gstin) DO UPDATE
    SET trade_name = EXCLUDED.trade_name,
        status = 'active',
        updated_at = NOW()
    RETURNING id INTO v_id;

    -- Link initial physical_site to this GST registration if site exists
    UPDATE physical_sites
    SET gst_reg_id = v_id
    WHERE tenant_id = p_tenant_id AND legal_entity_id = p_legal_entity_id AND gst_reg_id IS NULL;

    RETURN v_id;
END;
$$;

REVOKE ALL ON FUNCTION boss_register_gstin(UUID, UUID, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION boss_register_gstin(UUID, UUID, TEXT, TEXT) TO boss_platform_admin_role;

-- The deployed Platform Admin login is boss_admin. It is created outside Flyway.
-- Grant EXECUTE when that login already exists so revoking PUBLIC does not
-- remove the access the running admin service uses.
DO $boss_admin_invite_grants$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'boss_admin') THEN
    GRANT EXECUTE ON FUNCTION boss_create_owner_invite(UUID, TEXT, TEXT, TIMESTAMPTZ) TO boss_admin;
    GRANT EXECUTE ON FUNCTION boss_validate_invite_token(TEXT) TO boss_admin;
    GRANT EXECUTE ON FUNCTION boss_accept_owner_invite(TEXT, TEXT, TEXT, TEXT) TO boss_admin;
    GRANT EXECUTE ON FUNCTION boss_get_tenant_invitation_summary(UUID) TO boss_admin;
    GRANT EXECUTE ON FUNCTION boss_register_gstin(UUID, UUID, TEXT, TEXT) TO boss_admin;
  END IF;
END
$boss_admin_invite_grants$;
