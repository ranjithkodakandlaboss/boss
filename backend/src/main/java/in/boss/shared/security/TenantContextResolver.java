package in.boss.shared.security;

import in.boss.shared.context.BossContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Resolves a Firebase UID to a BossContext.
 *
 * Uses the SECURITY DEFINER function boss_resolve_firebase_uid() (V064) so the
 * lookup runs as the function owner and is not blocked by RLS on app_users.
 * Returns null if the UID is not found (unauthenticated request continues without context).
 */
@Component
public class TenantContextResolver {

    private static final Logger log = LoggerFactory.getLogger(TenantContextResolver.class);

    private final JdbcTemplate jdbc;

    public TenantContextResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BossContext resolve(String firebaseUid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT out_user_id, out_tenant_id, out_status, out_role, out_site_id " +
                "FROM boss_resolve_firebase_uid(?)",
                firebaseUid);

        if (rows.isEmpty()) return null;

        Map<String, Object> first = rows.get(0);
        if (!"active".equals(first.get("out_status"))) return null;

        UUID userId = UUID.fromString(first.get("out_user_id").toString());
        UUID tenantId = UUID.fromString(first.get("out_tenant_id").toString());

        // Guard against the same firebase_uid appearing in multiple tenants (data integrity violation).
        // V125 migration adds a unique index to prevent this, but we defend here as a safety net.
        for (var row : rows) {
            Object rowTenant = row.get("out_tenant_id");
            if (rowTenant != null && !tenantId.equals(UUID.fromString(rowTenant.toString()))) {
                log.error("SECURITY: firebase_uid {} maps to multiple tenants — denying access until data is corrected", firebaseUid);
                return null;
            }
        }

        Set<String> roles = new HashSet<>();
        UUID siteId = null;
        for (var row : rows) {
            if (row.get("out_role") != null) {
                roles.add(row.get("out_role").toString());
                if (siteId == null && row.get("out_site_id") != null) {
                    siteId = UUID.fromString(row.get("out_site_id").toString());
                }
            }
        }

        return new BossContext(tenantId, userId, Collections.unmodifiableSet(roles),
                siteId, firebaseUid);
    }

    /**
     * Returns the tenant_id for the given slug, or null if not found/active.
     * Used by FirebaseJwtFilter to enforce host-based tenant isolation.
     */
    public UUID tenantIdForSlug(String slug) {
        List<UUID> rows = jdbc.query(
                "SELECT id FROM tenant_registry WHERE slug = ? AND status = 'active'",
                (rs, i) -> rs.getObject("id", UUID.class), slug);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * True when this Firebase UID belongs to a user who is not active.
     * An unknown UID returns false so the caller can treat it as signed-out.
     */
    public boolean isInactive(String firebaseUid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT out_status FROM boss_resolve_firebase_uid(?)",
                firebaseUid);
        if (rows.isEmpty() || rows.get(0).get("out_status") == null) return false;
        return !"active".equals(rows.get(0).get("out_status").toString());
    }
}
