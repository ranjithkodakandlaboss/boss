package in.boss.shared.context;

import java.util.Set;
import java.util.UUID;

/**
 * Immutable per-request context propagated via BossContextHolder (ThreadLocal).
 * Cleared in finally blocks — never rely on it surviving a thread hop.
 */
public record BossContext(
        UUID tenantId,
        UUID userId,
        Set<String> roles,
        UUID siteId,
        String firebaseUid
) {
    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    public boolean hasAnyRole(String... required) {
        if (roles == null) return false;
        for (String r : required) {
            if (roles.contains(r)) return true;
        }
        return false;
    }
}
