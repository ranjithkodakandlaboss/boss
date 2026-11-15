package in.boss.shared.security;

import in.boss.shared.context.BossContext;
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
}
