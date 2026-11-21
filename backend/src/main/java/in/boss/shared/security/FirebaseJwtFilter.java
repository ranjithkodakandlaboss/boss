package in.boss.shared.security;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Validates Firebase ID tokens and populates BossContextHolder.
 * Tenant resolution is performed via the app_users table lookup.
 * BossContextHolder is always cleared in the finally block.
 */
@Component
public class FirebaseJwtFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseJwtFilter.class);

    private final FirebaseAuth firebaseAuth;
    private final TenantContextResolver tenantContextResolver;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.core.env.Environment environment;

    public FirebaseJwtFilter(FirebaseAuth firebaseAuth,
                             TenantContextResolver tenantContextResolver) {
        this.firebaseAuth = firebaseAuth;
        this.tenantContextResolver = tenantContextResolver;
    }

    private boolean isDevOrLocal() {
        if (environment == null) {
            return true;
        }
        return environment.matchesProfiles("local", "dev", "test")
                || environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local", "dev", "test"));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {
        try {
            if (isActivation(request)) {
                chain.doFilter(request, response);
                return;
            }
            String token = extractBearerToken(request);
            if (token != null) {
                try {
                    String uid;
                    if (isDevOrLocal() && token.startsWith("local-seed-")) {
                        uid = token;
                    } else {
                        FirebaseToken decoded = firebaseAuth.verifyIdToken(token);
                        uid = decoded.getUid();
                    }
                    BossContext ctx = tenantContextResolver.resolve(uid);

                    // Enforce tenant isolation: the slug from the request must match the user's tenant.
                    if (ctx != null) {
                        String slug = extractTenantSlug(request);
                        if (slug != null) {
                            UUID expectedTenant = tenantContextResolver.tenantIdForSlug(slug);
                            if (expectedTenant == null || !expectedTenant.equals(ctx.tenantId())) {
                                log.warn("SECURITY: firebase_uid {} (tenant {}) denied on slug '{}' (resolved tenant: {})",
                                        uid, ctx.tenantId(), slug, expectedTenant);
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                response.setContentType("application/json");
                                response.getWriter().write(
                                        "{\"title\":\"Forbidden\",\"status\":403,"
                                        + "\"detail\":\"Your account does not belong to this organisation\"}");
                                return;
                            }
                        }
                    }

                    if (ctx != null) {
                        BossContextHolder.set(ctx);
                        List<SimpleGrantedAuthority> authorities = ctx.roles().stream()
                                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                                .toList();
                        var auth = new UsernamePasswordAuthenticationToken(
                                ctx.firebaseUid(), null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    } else if (tenantContextResolver.isInactive(uid)) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json");
                        response.getWriter().write(
                                "{\"title\":\"Forbidden\",\"status\":403,"
                                + "\"detail\":\"User is suspended or deactivated\"}");
                        return;
                    } else {
                        // Firebase token is valid but this UID has no BOSS tenant association.
                        // Deny access explicitly — Firebase authentication alone is not sufficient.
                        log.debug("Firebase UID {} is not associated with any BOSS tenant", uid);
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.getWriter().write(
                                "{\"title\":\"Unauthorized\",\"status\":401,"
                                + "\"detail\":\"No BOSS account is associated with this identity\"}");
                        return;
                    }
                } catch (Exception ex) {
                    log.debug("JWT validation failed: {}", ex.getMessage());
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            BossContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isActivation(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && "/api/v1/auth/activate".equals(request.getRequestURI());
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    /**
     * Extracts the tenant slug. Reads X-Boss-Tenant header first (set by the
     * frontend from window.location.hostname), then falls back to parsing the
     * Host/X-Forwarded-Host header. Returns null in local dev (no isolation).
     */
    private String extractTenantSlug(HttpServletRequest request) {
        // Preferred: client-sent slug derived from window.location.hostname
        String clientSlug = request.getHeader("X-Boss-Tenant");
        if (clientSlug != null && !clientSlug.isBlank()) {
            return clientSlug.trim().toLowerCase();
        }
        // Fallback: parse host header (may be rewritten by proxies)
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) host = request.getServerName();
        if (host == null) return null;
        int colon = host.indexOf(':');
        if (colon != -1) host = host.substring(0, colon);
        if (host.equals("localhost") || host.startsWith("127.")) return null;
        int dot = host.indexOf('.');
        if (dot <= 0) return null;
        return host.substring(0, dot);
    }
}
