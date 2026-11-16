package in.boss.shared.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fail-closed policy:
 *   - Tenant not found in DB → returns "unknown" (blocked by interceptor).
 *   - DB error                → returns "unknown" and retries on the next request.
 * Never defaults to "active" on error — that would allow unknown or forged
 * tenant IDs to pass through unchecked.
 */
@Component
public class TenantStatusCache {

    private static final Logger log = LoggerFactory.getLogger(TenantStatusCache.class);

    public static final String STATUS_UNKNOWN = "unknown";

    private final JdbcTemplate jdbc;
    public TenantStatusCache(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String getStatus(UUID tenantId) {
        // Suspension is security-sensitive and must apply to the next request.
        // Platform Admin and customer API run in separate processes, so a local
        // cache cannot be reliably invalidated when an administrator changes status.
        return fetchStatus(tenantId);
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
}
