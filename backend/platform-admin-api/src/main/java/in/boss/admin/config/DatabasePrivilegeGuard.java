package in.boss.admin.config;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DatabasePrivilegeGuard {
    private static final String TABLE_PRIVILEGES = """
            SELECT c.relname || ':' || p.privilege
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            CROSS JOIN (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE'),
                               ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')) AS p(privilege)
            LEFT JOIN (VALUES
              ('customer_groups','INSERT'),
              ('tenant_registry','SELECT'), ('tenant_registry','INSERT'),
              ('legal_entities','INSERT'), ('physical_sites','INSERT'),
              ('tenant_onboarding_config','SELECT'), ('tenant_onboarding_config','INSERT'),
              ('platform_admin_users','SELECT'),
              ('platform_audit_events','SELECT'), ('platform_audit_events','INSERT'),
              ('idempotency_keys','SELECT'), ('idempotency_keys','INSERT'),
              ('idempotency_keys','UPDATE'), ('idempotency_keys','DELETE'),
              ('industry_template_options','SELECT'), ('config_categories','SELECT'),
              ('tenant_config_options','SELECT'), ('tenant_config_options','INSERT'),
              ('units_of_measure','SELECT'), ('units_of_measure','INSERT'),
              ('item_categories','SELECT'), ('item_categories','INSERT')
            ) AS allowed(table_name, privilege)
              ON allowed.table_name = c.relname AND allowed.privilege = p.privilege
            WHERE n.nspname = 'public'
              AND c.relkind IN ('r', 'p', 'v', 'm')
              AND has_table_privilege(current_user, c.oid, p.privilege)
              AND allowed.table_name IS NULL
            UNION ALL
            SELECT c.relname || ':' || p.privilege
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            CROSS JOIN (VALUES ('USAGE'), ('SELECT'), ('UPDATE')) AS p(privilege)
            WHERE n.nspname = 'public'
              AND c.relkind = 'S'
              AND has_sequence_privilege(current_user, c.oid, p.privilege)
            """;

    @Bean
    ApplicationRunner verifyPlatformAdminDatabaseRole(JdbcTemplate jdbc) {
        return (ApplicationArguments args) -> {
            String principal = jdbc.queryForObject("SELECT current_user", String.class);
            if (!"boss_admin".equals(principal)) {
                throw new IllegalStateException("Platform Admin must connect as the dedicated boss_admin role");
            }
            Boolean unsafeRole = jdbc.queryForObject("""
                    SELECT EXISTS (
                      SELECT 1 FROM pg_roles r
                      WHERE (r.rolsuper OR r.rolbypassrls)
                        AND (r.rolname = current_user OR pg_has_role(current_user, r.oid, 'MEMBER'))
                    )
                    """, Boolean.class);
            if (!Boolean.FALSE.equals(unsafeRole)) {
                throw new IllegalStateException("Platform Admin database role must be NOSUPERUSER and NOBYPASSRLS");
            }
            if (!jdbc.queryForObject("SELECT has_schema_privilege(current_user, 'public', 'USAGE')", Boolean.class)) {
                throw new IllegalStateException("Platform Admin database role must have USAGE on public schema");
            }
            List<String> unexpected = jdbc.queryForList(TABLE_PRIVILEGES, String.class);
            if (!unexpected.isEmpty()) {
                throw new IllegalStateException("Platform Admin database role has non-control-plane privileges: " + unexpected);
            }
            List<String> missing = jdbc.queryForList("""
                    SELECT required.table_name || ':' || required.privilege
                    FROM (VALUES
                      ('customer_groups','INSERT'),
                      ('tenant_registry','SELECT'), ('tenant_registry','INSERT'),
                      ('legal_entities','INSERT'), ('physical_sites','INSERT'),
                      ('tenant_onboarding_config','SELECT'), ('tenant_onboarding_config','INSERT'),
                      ('platform_admin_users','SELECT'),
                      ('platform_audit_events','SELECT'), ('platform_audit_events','INSERT'),
                      ('idempotency_keys','SELECT'), ('idempotency_keys','INSERT'),
                      ('idempotency_keys','UPDATE'), ('idempotency_keys','DELETE'),
                      ('industry_template_options','SELECT'), ('config_categories','SELECT'),
                      ('tenant_config_options','SELECT'), ('tenant_config_options','INSERT'),
                      ('units_of_measure','SELECT'), ('units_of_measure','INSERT'),
                      ('item_categories','SELECT'), ('item_categories','INSERT')
                    ) AS required(table_name, privilege)
                    WHERE NOT has_table_privilege(current_user,
                          'public.' || required.table_name, required.privilege)
                    ORDER BY required.table_name, required.privilege
                    """, String.class);
            if (!jdbc.queryForObject("SELECT has_column_privilege(current_user, 'public.tenant_registry', 'status', 'UPDATE') " +
                    "AND has_column_privilege(current_user, 'public.tenant_registry', 'updated_at', 'UPDATE') " +
                    "AND has_column_privilege(current_user, 'public.tenant_registry', 'initial_admin_email', 'UPDATE') " +
                    "AND has_column_privilege(current_user, 'public.tenant_registry', 'name', 'UPDATE') " +
                    "AND has_column_privilege(current_user, 'public.customer_groups', 'id', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.customer_groups', 'name', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.customer_groups', 'slug', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.tenant_registry', 'id', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.legal_entities', 'id', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.legal_entities', 'tenant_id', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.legal_entities', 'name', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.legal_entities', 'legal_name', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.legal_entities', 'pan', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.legal_entities', 'entity_type', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.legal_entities', 'registered_address', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.legal_entities', 'status', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.legal_entities', 'name', 'UPDATE') " +
                    "AND has_column_privilege(current_user, 'public.legal_entities', 'legal_name', 'UPDATE') " +
                    "AND has_column_privilege(current_user, 'public.legal_entities', 'registered_address', 'UPDATE') " +
                    "AND has_column_privilege(current_user, 'public.gst_registrations', 'id', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.gst_registrations', 'tenant_id', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.gst_registrations', 'legal_entity_id', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.gst_registrations', 'gstin', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.gst_registrations', 'trade_name', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.gst_registrations', 'registration_type', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.gst_registrations', 'state_code', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.gst_registrations', 'status', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.gst_registrations', 'effective_from', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.gst_registrations', 'effective_to', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.physical_sites', 'id', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.physical_sites', 'tenant_id', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.physical_sites', 'legal_entity_id', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.physical_sites', 'gst_reg_id', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.physical_sites', 'name', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.physical_sites', 'code', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.physical_sites', 'site_type', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.physical_sites', 'address', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.physical_sites', 'status', 'SELECT') " +
                    "AND has_column_privilege(current_user, 'public.physical_sites', 'name', 'UPDATE') " +
                    "AND has_column_privilege(current_user, 'public.physical_sites', 'address', 'UPDATE') " +
                    "AND has_column_privilege(current_user, 'public.tenant_onboarding_config', 'industry_type', 'UPDATE') " +
                    "AND has_column_privilege(current_user, 'public.tenant_onboarding_config', 'onboarding_completed_at', 'UPDATE')",
                    Boolean.class)) {
                missing.add("required customer-detail read/write column privileges");
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException("Platform Admin database role is missing required control-plane privileges: " + missing);
            }
            Integer listPolicies = jdbc.queryForObject("""
                    SELECT count(*)
                    FROM pg_policy pol
                    JOIN pg_class c ON c.oid = pol.polrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public'
                      AND pol.polcmd = 'r'
                      AND (
                        (c.relname = 'tenant_registry' AND pol.polname = 'tenant_registry_platform_admin_read')
                        OR (c.relname = 'tenant_onboarding_config'
                            AND pol.polname = 'tenant_onboarding_config_platform_admin_read')
                      )
                      AND EXISTS (
                        SELECT 1 FROM pg_roles role
                        WHERE role.oid = ANY (pol.polroles)
                          AND pg_has_role(current_user, role.oid, 'MEMBER')
                      )
                    """, Integer.class);
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT has_function_privilege(current_user, 'public.seed_tenant_defaults(uuid, text)', 'EXECUTE')",
                    Boolean.class))) {
                throw new IllegalStateException(
                        "Platform Admin must be able to execute seed_tenant_defaults. Apply db/boss-admin-grants.sql.");
            }
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT has_function_privilege(current_user, 'public.boss_create_owner_invite(uuid, text, text, timestamptz)', 'EXECUTE')",
                    Boolean.class))) {
                throw new IllegalStateException(
                        "Platform Admin must be able to execute boss_create_owner_invite. Apply db/boss-admin-grants.sql.");
            }
            if (listPolicies == null || listPolicies != 2) {
                throw new IllegalStateException(
                        "Platform Admin list policies are missing. Apply db/boss-admin-grants.sql before starting.");
            }
            Integer detailPolicies = jdbc.queryForObject("""
                    SELECT count(*)
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_policy pol ON pol.polrelid = c.oid
                    WHERE n.nspname = 'public'
                      AND c.relrowsecurity
                      AND c.relforcerowsecurity
                      AND (
                        (c.relname = 'legal_entities' AND pol.polname = 'legal_entities_isolation')
                        OR (c.relname = 'gst_registrations' AND pol.polname = 'gst_registrations_isolation')
                        OR (c.relname = 'physical_sites' AND pol.polname = 'physical_sites_isolation')
                      )
                    """, Integer.class);
            if (detailPolicies == null || detailPolicies != 3) {
                throw new IllegalStateException(
                        "Tenant RLS policies are required for company and site details");
            }
        };
    }
}
