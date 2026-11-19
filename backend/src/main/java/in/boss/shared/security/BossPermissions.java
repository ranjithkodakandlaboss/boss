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
    public static final String FIELD_WORKER =
            "hasRole('FIELD_WORKER')";
    public static final String PLATFORM_ADMIN =
            "hasRole('PLATFORM_ADMIN')";

    // ---- common combinations used across domains ----

    /** Owner or Ops Manager — broad operational authority. */
    public static final String OWNER_OR_OPS =
            "hasAnyRole('OWNER','OPS_MANAGER')";

    /** Concession approval — FD-approved policy strictly limits to OWNER or OPS_MANAGER. */
    public static final String CONCESSION_APPROVE =
            "hasAnyRole('OWNER','OPS_MANAGER')";

    /**
     * Commercial data access (material costs, margins, selling prices).
     * Excludes floor-level roles (PRODUCTION_SUPERVISOR, STORE_KEEPER, QUALITY_INSPECTOR, FIELD_WORKER).
     */
    public static final String COMMERCIAL_DATA_READ =
            "hasAnyRole('OWNER','OPS_MANAGER','SALES_MANAGER','PURCHASE_MANAGER','ACCOUNTS_STAFF')";

    /** Owner, Ops Manager or Sales Manager — sales-oriented access. */
    public static final String SALES_ACCESS =
            "hasAnyRole('OWNER','OPS_MANAGER','SALES_MANAGER')";

    public static final String SALES_WRITE =
            "hasAnyRole('OWNER','OPS_MANAGER','SALES_MANAGER')";

    /** CPO state transitions are limited to the commercial owners defined by the state machine. */
    public static final String CPO_TRANSITIONS =
            "hasAnyRole('OWNER','SALES_MANAGER')";

    /** Owner, Ops Manager or Purchase Manager — procurement access. */
    public static final String PURCHASE_ACCESS =
            "hasAnyRole('OWNER','OPS_MANAGER','PURCHASE_MANAGER')";

    public static final String PURCHASE_WRITE =
            "hasAnyRole('OWNER','OPS_MANAGER','PURCHASE_MANAGER')";

    /** Supplier quotation and SPO management — Purchase Manager or Owner. */
    public static final String PROCUREMENT_MANAGE =
            "hasAnyRole('OWNER','PURCHASE_MANAGER')";

    /** PR access — roles permitted to create and view purchase requisitions. */
    public static final String PR_ACCESS =
            "hasAnyRole('OWNER','OPS_MANAGER','PURCHASE_MANAGER','PRODUCTION_SUPERVISOR','STORE_KEEPER')";

    public static final String PR_WRITE =
            "hasAnyRole('OWNER','OPS_MANAGER','PURCHASE_MANAGER','PRODUCTION_SUPERVISOR','STORE_KEEPER')";

    /** PR approval and rejection — restricted to Purchase Manager, Ops Manager, or Owner. */
    public static final String PR_APPROVE =
            "hasAnyRole('OWNER','OPS_MANAGER','PURCHASE_MANAGER')";

    /** Anyone who can create or manage production jobs. */
    public static final String PRODUCTION_ACCESS =
            "hasAnyRole('OWNER','OPS_MANAGER','PRODUCTION_SUPERVISOR')";

    /** Read production jobs, including shop-floor and quality roles. */
    public static final String PRODUCTION_VIEW =
            "hasAnyRole('OWNER','OPS_MANAGER','PRODUCTION_SUPERVISOR','FIELD_WORKER','QUALITY_INSPECTOR')";

    public static final String PRODUCTION_WRITE =
            "hasAnyRole('OWNER','OPS_MANAGER','PRODUCTION_SUPERVISOR')";

    /** Start or record stage execution, including the shop-floor role. */
    public static final String PRODUCTION_EXECUTE =
            "hasAnyRole('OWNER','OPS_MANAGER','PRODUCTION_SUPERVISOR','FIELD_WORKER')";

    /** Complete a stage or the job. Quality can close the final gate. */
    public static final String PRODUCTION_COMPLETE =
            "hasAnyRole('OWNER','OPS_MANAGER','PRODUCTION_SUPERVISOR','QUALITY_INSPECTOR')";

    /** Read assets and maintenance events. */
    public static final String MAINTENANCE_VIEW =
            "hasAnyRole('OWNER','OPS_MANAGER','PRODUCTION_SUPERVISOR','FIELD_WORKER','QUALITY_INSPECTOR')";

    /** Manage assets and maintenance events. */
    public static final String MAINTENANCE_WRITE =
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

    public static final String QUALITY_WRITE =
            "hasAnyRole('OWNER','OPS_MANAGER','QUALITY_INSPECTOR')";

    /** Anyone who can read or write invoices and receipts. */
    public static final String ACCOUNTS_ACCESS =
            "hasAnyRole('OWNER','OPS_MANAGER','ACCOUNTS_STAFF')";

    /** Read dispatch records. */
    public static final String DISPATCH_VIEW =
            "hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER','SALES_MANAGER','PRODUCTION_SUPERVISOR','FIELD_WORKER')";

    /** Create, pack, dispatch, and record delivery for dispatches. */
    public static final String DISPATCH_WRITE =
            "hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER')";

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
