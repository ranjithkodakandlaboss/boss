package in.boss.shared.tenant;

import in.boss.shared.context.BossContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.UUID;

/**
 * Rejects requests from suspended tenants with HTTP 403.
 * Runs after FirebaseJwtFilter has populated BossContextHolder.
 * Tenant status is cached by TenantStatusCache to avoid per-request DB hits.
 */
@Component
public class SuspendedTenantInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SuspendedTenantInterceptor.class);

    private final TenantStatusCache statusCache;

    public SuspendedTenantInterceptor(TenantStatusCache statusCache) {
        this.statusCache = statusCache;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws IOException {
        var ctx = BossContextHolder.get();
        if (ctx == null || ctx.tenantId() == null) {
            return true;  // unauthenticated — let security handle it
        }

        UUID tenantId = ctx.tenantId();
        String status = statusCache.getStatus(tenantId);

        if ("suspended".equals(status) || "archived".equals(status)) {
            log.warn("Blocking request for {} tenant={}", status, tenantId);
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write(
                    "{\"error\":\"TENANT_SUSPENDED\","
                    + "\"message\":\"Your account has been suspended. Please contact support.\"}");
            return false;
        }
        return true;
    }
}
