package in.boss.shared.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.shared.context.BossContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Idempotency-Key middleware (issue #96).
 *
 * Checks the {@code Idempotency-Key} header on POST, PUT, PATCH requests.
 * If a matching key+tenant exists in idempotency_keys and the response is cached,
 * returns the cached response immediately. Otherwise, processes the request and
 * stores the response for 24 hours.
 *
 * Key format: UUID-shaped string, max 128 chars.
 * Scope: per tenant (tenant_id + key must be unique).
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final List<String> MUTATING_METHODS = List.of("POST", "PUT", "PATCH");
    private static final int MAX_KEY_LENGTH = 128;
    private static final int TTL_HOURS = 24;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyFilter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !MUTATING_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String idempotencyKey = request.getHeader("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"INVALID_IDEMPOTENCY_KEY\","
                    + "\"message\":\"Idempotency-Key must be <= 128 characters.\"}");
            return;
        }

        var ctx = BossContextHolder.get();
        UUID tenantId = ctx != null ? ctx.tenantId() : null;

        // Check for existing cached response
        Map<String, Object> existing = lookupKey(tenantId, idempotencyKey);
        if (existing != null) {
            replayResponse(existing, response);
            return;
        }

        // Wrap request and response to capture body
        var wrappedRequest = new ContentCachingRequestWrapper(request);
        var wrappedResponse = new ContentCachingResponseWrapper(response);

        // Process the actual request
        chain.doFilter(wrappedRequest, wrappedResponse);

        // Store the response (best-effort — don't fail the request if storage fails)
        int status = wrappedResponse.getStatus();
        if (status >= 200 && status < 300) {
            byte[] responseBody = wrappedResponse.getContentAsByteArray();
            String requestHash = hashRequestBody(wrappedRequest.getContentAsByteArray(),
                                                  request.getRequestURI());
            storeKey(tenantId, idempotencyKey, requestHash, status, responseBody);
        }

        wrappedResponse.copyBodyToResponse();
    }

    private Map<String, Object> lookupKey(UUID tenantId, String key) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT response_status, response_body::text FROM idempotency_keys "
                    + "WHERE tenant_id = ? AND key = ? AND expires_at > NOW()",
                    tenantId, key);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.debug("Idempotency lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private void replayResponse(Map<String, Object> cached, HttpServletResponse response)
            throws IOException {
        int status = ((Number) cached.get("response_status")).intValue();
        String body = (String) cached.get("response_body");
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("X-Idempotent-Replayed", "true");
        if (body != null && !body.isBlank()) {
            response.getWriter().write(body);
        }
    }

    private void storeKey(UUID tenantId, String key, String requestHash,
                          int status, byte[] body) {
        try {
            String bodyJson = body != null && body.length > 0
                    ? new String(body, StandardCharsets.UTF_8)
                    : null;
            jdbc.update(
                    "INSERT INTO idempotency_keys "
                    + "(tenant_id, key, request_hash, response_status, response_body, expires_at) "
                    + "VALUES (?, ?, ?, ?, ?::jsonb, ?) "
                    + "ON CONFLICT (tenant_id, key) DO NOTHING",
                    tenantId, key, requestHash, status, bodyJson,
                    Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS));
        } catch (DuplicateKeyException e) {
            // Race: another thread stored the same key; ignore
        } catch (Exception e) {
            log.warn("Failed to store idempotency key={}: {}", key, e.getMessage());
        }
    }

    private static String hashRequestBody(byte[] body, String uri) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(uri.getBytes(StandardCharsets.UTF_8));
            md.update(body != null ? body : new byte[0]);
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "unknown";
        }
    }
}
