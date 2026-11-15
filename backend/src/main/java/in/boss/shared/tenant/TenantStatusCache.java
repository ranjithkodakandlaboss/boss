package in.boss.shared.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches tenant status from tenant_registry with a TTL of 60 seconds.
 *
 * Fail-closed policy:
 *   - Tenant not found in DB → returns "unknown" (blocked by interceptor).
 *   - DB error                → returns "unknown" with a short 5-second TTL,
 *                               allowing the system to recover quickly without
 *                               blocking every request indefinitely.
 * Never defaults to "active" on error — that would allow unknown or forged
 * tenant IDs to pass through unchecked.
 */
@Component
public class TenantStatusCache {

    private static final Logger log = LoggerFactory.getLogger(TenantStatusCache.class);

    public static final long TTL_MS = 60_000L;
    public static final long ERROR_TTL_MS = 5_000L;
    public static final String STATUS_UNKNOWN = "unknown";

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
        long ttl = STATUS_UNKNOWN.equals(status) ? ERROR_TTL_MS : TTL_MS;
        cache.put(tenantId, new CachedStatus(status, ttl));
        return status;
    }

    /** Evict immediately — call after a tenant status change. */
    public void evict(UUID tenantId) {
        cache.remove(tenantId);
    }

    private String fetchStatus(UUID tenantId) {
        try {
            String status = jdbc.queryForObject(
                    "SELECT status FROM tenant_registry WHERE id = ?",
                    String.class, tenantId);
            return status != null ? status : STATUS_UNKNOWN;
        } catch (EmptyResultDataAccessException e) {
            log.warn("Tenant not found in registry: {} — blocking as unknown", tenantId);
            return STATUS_UNKNOWN;
        } catch (Exception e) {
            log.error("Failed to fetch tenant status for {} — failing closed: {}", tenantId, e.getMessage());
            return STATUS_UNKNOWN;
        }
    }

    private static final class CachedStatus {
        final String status;
        final long expiresAt;

        CachedStatus(String status, long ttlMs) {
            this.status = status;
            this.expiresAt = Instant.now().toEpochMilli() + ttlMs;
        }

        boolean isExpired() {
            return Instant.now().toEpochMilli() > expiresAt;
        }
    }
}
