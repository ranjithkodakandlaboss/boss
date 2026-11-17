package in.boss.shared.security;

import java.util.Collections;
import java.util.Set;

/**
 * Canonical role constants used in @PreAuthorize and permission checks.
 *
 * Exactly nine approved tenant roles exist in the tenant boundary.
 * PLATFORM_ADMIN belongs strictly to boss-admin-api / platform_admin_users,
 * never in customer app user_roles.
 */
public final class BossRoles {
    public static final String OWNER              = "OWNER";
    public static final String OPS_MANAGER        = "OPS_MANAGER";
    public static final String SALES_MANAGER      = "SALES_MANAGER";
    public static final String PURCHASE_MANAGER   = "PURCHASE_MANAGER";
    public static final String PRODUCTION_SUPERVISOR = "PRODUCTION_SUPERVISOR";
    public static final String STORE_KEEPER       = "STORE_KEEPER";
    public static final String ACCOUNTS_STAFF     = "ACCOUNTS_STAFF";
    public static final String QUALITY_INSPECTOR  = "QUALITY_INSPECTOR";
    public static final String FIELD_WORKER       = "FIELD_WORKER";

    /**
     * Unmodifiable set of the exactly nine approved tenant roles.
     */
    public static final Set<String> TENANT_ROLES = Collections.unmodifiableSet(Set.of(
            OWNER, OPS_MANAGER, SALES_MANAGER, PURCHASE_MANAGER,
            PRODUCTION_SUPERVISOR, STORE_KEEPER, ACCOUNTS_STAFF,
            QUALITY_INSPECTOR, FIELD_WORKER
    ));

    // Admin roles — only valid in boss-admin-api, never in boss-api (customer app)
    public static final String PLATFORM_ADMIN     = "PLATFORM_ADMIN";

    private BossRoles() {}
}
