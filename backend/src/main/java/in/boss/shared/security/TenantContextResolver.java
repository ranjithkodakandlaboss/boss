package in.boss.shared.security;

import in.boss.shared.context.BossContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Resolves a Firebase UID to a BossContext by querying app_users + user_roles.
 * Returns null if the UID is not found (unauthenticated request continues without context).
 */
@Component
public class TenantContextResolver {

    private final JdbcTemplate jdbc;

    public TenantContextResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BossContext resolve(String firebaseUid) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.id, u.tenant_id, u.status, r.role, r.site_id
                FROM app_users u
                LEFT JOIN user_roles r ON r.user_id = u.id AND r.tenant_id = u.tenant_id
                WHERE u.firebase_uid = ?
                """, firebaseUid);

        if (rows.isEmpty()) return null;

        Map<String, Object> first = rows.get(0);
        if (!"active".equals(first.get("status"))) return null;

        UUID userId = UUID.fromString(first.get("id").toString());
        UUID tenantId = UUID.fromString(first.get("tenant_id").toString());

        Set<String> roles = new HashSet<>();
        UUID siteId = null;
        for (var row : rows) {
            if (row.get("role") != null) {
                roles.add(row.get("role").toString());
                if (siteId == null && row.get("site_id") != null) {
                    siteId = UUID.fromString(row.get("site_id").toString());
                }
            }
        }

        return new BossContext(tenantId, userId, Collections.unmodifiableSet(roles),
                siteId, firebaseUid);
    }
}
