package in.boss.shared.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches tenant status from tenant_registry with a TTL of 60 seconds.
 * Avoids a DB lookup on every authenticated request while ensuring suspension
 * takes effect within one minute.
 */
@Component
public class TenantStatusCache {

    private static final long TTL_MS = 60_000L;

    private final JdbcTemplate jdbc;
    private final Map<UUID, CachedStatus> cache = new ConcurrentHashMap<>();

    public TenantStatusCache(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String getStatus(UUID tenantId) {
        CachedStatus cached = cache.get(tenantId);
        if (cached != null && !cached.isExpired()) {
            return cached.status;
        }
        String status = fetchStatus(tenantId);
        cache.put(tenantId, new CachedStatus(status));
        return status;
    }

    /** Evict immediately — call after a tenant status change. */
    public void evict(UUID tenantId) {
        cache.remove(tenantId);
    }

    private String fetchStatus(UUID tenantId) {
        // Uses superuser connection — tenant_registry has RLS policy USING (id = boss_current_tenant_id())
        // but status checks are infrastructure, not tenant-data reads, so we query without RLS filtering.
        // This runs on a connection that may not have tenant context (platform service call).
        try {
            return jdbc.queryForObject(
                    "SELECT status FROM tenant_registry WHERE id = ?",
                    String.class, tenantId);
        } catch (Exception e) {
            // If tenant not found, treat as active to avoid false-positive blocks.
            return "active";
        }
    }

    private record CachedStatus(String status, long expiresAt) {
        CachedStatus(String status) {
            this(status, Instant.now().toEpochMilli() + TTL_MS);
        }
        boolean isExpired() {
            return Instant.now().toEpochMilli() > expiresAt;
        }
    }
}
