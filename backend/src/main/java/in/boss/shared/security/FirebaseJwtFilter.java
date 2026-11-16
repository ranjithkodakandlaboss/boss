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

    public FirebaseJwtFilter(FirebaseAuth firebaseAuth,
                             TenantContextResolver tenantContextResolver) {
        this.firebaseAuth = firebaseAuth;
        this.tenantContextResolver = tenantContextResolver;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {
        try {
            String token = extractBearerToken(request);
            if (token != null) {
                try {
                    FirebaseToken decoded = firebaseAuth.verifyIdToken(token);
                    BossContext ctx = tenantContextResolver.resolve(decoded.getUid());

                    if (ctx != null) {
                        BossContextHolder.set(ctx);
                        List<SimpleGrantedAuthority> authorities = ctx.roles().stream()
                                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                                .toList();
                        var auth = new UsernamePasswordAuthenticationToken(
                                ctx.firebaseUid(), null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(auth);
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

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
