package in.boss.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminAuthenticationFilter extends OncePerRequestFilter {
    private final AdminTokenVerifier verifier;
    private final JdbcTemplate jdbcTemplate;

    public AdminAuthenticationFilter(AdminTokenVerifier verifier, JdbcTemplate jdbcTemplate) {
        this.verifier = verifier;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().equals("/actuator/health")
                || request.getRequestURI().startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        try {
            AdminTokenVerifier.VerifiedAdminToken token = verifier.verify(authorization.substring(7));
            List<AdminPrincipal> admins = jdbcTemplate.query(
                    "SELECT id, firebase_uid, email FROM platform_admin_users " +
                            "WHERE firebase_uid = ? AND status = 'active'",
                    (rs, row) -> new AdminPrincipal(rs.getObject("id", UUID.class),
                            rs.getString("firebase_uid"), rs.getString("email")), token.uid());
            if (admins.size() != 1) throw new BadCredentialsException("Platform administrator is not active");
            AdminPrincipal principal = admins.getFirst();
            var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                    List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } catch (FirebaseAdminTokenVerifier.CustomerProjectTokenException exception) {
            SecurityContextHolder.clearContext();
            response.sendError(HttpServletResponse.SC_FORBIDDEN, exception.getMessage());
        } catch (InsufficientAuthenticationException | BadCredentialsException exception) {
            SecurityContextHolder.clearContext();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
