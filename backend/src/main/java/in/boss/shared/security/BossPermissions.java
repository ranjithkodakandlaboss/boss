package in.boss.shared.security;

/**
 * Pre-built SpEL expressions for {@code @PreAuthorize} on domain service methods.
 *
 * Usage:
 * <pre>
 *   {@literal @}PreAuthorize(BossPermissions.OWNER_OR_OPS)
 *   public void createProductionJob(...) { ... }
 * </pre>
 *
 * Roles are prefixed with ROLE_ in Spring Security; these expressions match the
 * granted authority names set by FirebaseJwtFilter.
 */
public final class BossPermissions {

    // ---- single-role predicates ----
    public static final String OWNER =
            "hasRole('OWNER')";
    public static final String OPS_MANAGER =
            "hasRole('OPS_MANAGER')";
    public static final String SALES_MANAGER =
            "hasRole('SALES_MANAGER')";
    public static final String PURCHASE_MANAGER =
            "hasRole('PURCHASE_MANAGER')";
    public static final String PRODUCTION_SUPERVISOR =
            "hasRole('PRODUCTION_SUPERVISOR')";
    public static final String STORE_KEEPER =
            "hasRole('STORE_KEEPER')";
    public static final String ACCOUNTS_STAFF =
            "hasRole('ACCOUNTS_STAFF')";
    public static final String QUALITY_INSPECTOR =
            "hasRole('QUALITY_INSPECTOR')";
    public static final String PLATFORM_ADMIN =
            "hasRole('PLATFORM_ADMIN')";

    // ---- common combinations used across domains ----

    /** Owner or Ops Manager — broad operational authority. */
    public static final String OWNER_OR_OPS =
            "hasAnyRole('OWNER','OPS_MANAGER')";

    /** Owner, Ops Manager or Sales Manager — sales-oriented access. */
    public static final String SALES_ACCESS =
            "hasAnyRole('OWNER','OPS_MANAGER','SALES_MANAGER')";

    /** Owner, Ops Manager or Purchase Manager — procurement access. */
    public static final String PURCHASE_ACCESS =
            "hasAnyRole('OWNER','OPS_MANAGER','PURCHASE_MANAGER')";

    /** Anyone who can create or manage production jobs. */
    public static final String PRODUCTION_ACCESS =
            "hasAnyRole('OWNER','OPS_MANAGER','PRODUCTION_SUPERVISOR')";

    /** Anyone who can touch stock or warehouse records. */
    public static final String INVENTORY_WRITE =
            "hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER')";

    /** Read-only inventory access. */
    public static final String INVENTORY_READ =
            "hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER','PRODUCTION_SUPERVISOR')";

    /** Anyone who can raise or manage quality inspections. */
    public static final String QUALITY_ACCESS =
            "hasAnyRole('OWNER','OPS_MANAGER','QUALITY_INSPECTOR','PRODUCTION_SUPERVISOR')";

    /** Anyone who can read or write invoices and receipts. */
    public static final String ACCOUNTS_ACCESS =
            "hasAnyRole('OWNER','OPS_MANAGER','ACCOUNTS_STAFF')";

    /** Any authenticated tenant user (minimum bar). */
    public static final String ANY_TENANT_USER =
            "isAuthenticated()";

    // ---- owner-only configuration gates ----

    /** Only the OWNER role may change tenant configuration options. */
    public static final String CONFIG_WRITE =
            "hasRole('OWNER')";

    /** Owner, Ops Manager, or Accounts may read invoices. */
    public static final String INVOICE_READ =
            "hasAnyRole('OWNER','OPS_MANAGER','ACCOUNTS_STAFF','SALES_MANAGER')";

    private BossPermissions() {}
}
