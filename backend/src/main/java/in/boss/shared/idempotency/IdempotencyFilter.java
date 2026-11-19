package in.boss.shared.idempotency;

import in.boss.shared.context.BossContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import org.springframework.util.AntPathMatcher;

import java.io.*;
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
 * Contract:
 *   - POST/PUT/PATCH to /api/** MUST include Idempotency-Key (400 if missing/invalid).
 *   - Same key + same request hash       → replays original response (200 with X-Idempotent-Replayed).
 *   - Same key + different request hash  → 409 Conflict (key reuse with different payload).
 *   - Concurrent requests with same key  → second request gets 503 (retry after first completes).
 *   - Idempotency store unavailable      → 503 (fail closed — never silently proceed).
 *
 * Key scoping:
 *   - Tenant requests: scoped to (tenant_id, key).
 *   - Platform operations (no tenant context): scoped to (NULL, key).
 *
 * Concurrency protocol:
 *   1. INSERT row with lock_status='locked' (V065 column).
 *   2. If conflict → examine existing row (hash match? lock state?).
 *   3. After successful execution → UPDATE lock_status='complete', store response.
 *   4. Any DB error at claim or store time → 503 (fail closed).
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final List<String> MUTATING_METHODS = List.of("POST", "PUT", "PATCH");
    private static final int MAX_KEY_LENGTH = 128;
    private static final int TTL_HOURS = 24;

    // WhatsApp webhook uses X-WhatsApp-Message-ID for deduplication (per whatsapp.yaml).
    // Config reads are GET-only and already exempt by method check.
    // BOM explosion is a pure calculation — no state mutations — so it is exempt regardless of method.
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final List<String> EXEMPT_PATHS = List.of(
        "/api/*/messaging/webhook/whatsapp",
        "/api/*/messaging/webhook/whatsapp/**",
        "/api/*/items/*/bom/explode"
    );

    private final JdbcTemplate jdbc;

    public IdempotencyFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!MUTATING_METHODS.contains(request.getMethod())) return true;
        String path = request.getRequestURI();
        return EXEMPT_PATHS.stream().anyMatch(p -> PATH_MATCHER.match(p, path));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String key = request.getHeader("Idempotency-Key");

        if (key == null || key.isBlank()) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "MISSING_IDEMPOTENCY_KEY",
                    "Idempotency-Key header is required for this operation.");
            return;
        }
        if (key.length() > MAX_KEY_LENGTH) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be <= " + MAX_KEY_LENGTH + " characters.");
            return;
        }

        // Read body eagerly for regular payloads so we can compute the hash.
        // For multipart requests, do NOT consume or close request.getInputStream() because
        // the servlet container / multipart resolver requires the stream to parse parts.
        boolean multipart = isMultipart(request);
        byte[] bodyBytes = multipart ? null : readBodyBytes(request);
        String requestHash = hashRequest(bodyBytes, request.getRequestURI(), request.getMethod(), request);

        var ctx = BossContextHolder.get();
        UUID tenantId = ctx != null ? ctx.tenantId() : null;

        ClaimResult claim = claimKey(tenantId, key, requestHash);
        switch (claim.outcome()) {
            case CLAIMED -> executeAndStore(request, response, chain, bodyBytes, tenantId, key, claim.rowId());
            case REPLAY  -> replayResponse(claim.existing(), response);
            case HASH_MISMATCH -> writeError(response, HttpServletResponse.SC_CONFLICT,
                    "IDEMPOTENCY_KEY_REUSE",
                    "Idempotency-Key has already been used with a different request payload.");
            case CONCURRENT -> writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "CONCURRENT_REQUEST",
                    "A concurrent request with this Idempotency-Key is in progress. Retry after a moment.");
            case STORE_ERROR -> writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "IDEMPOTENCY_STORE_UNAVAILABLE",
                    "Cannot process request: idempotency store is temporarily unavailable.");
        }
    }

    // ---- claim ----

    private ClaimResult claimKey(UUID tenantId, String key, String requestHash) {
        try {
            Instant expiresAt = Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS);

            // Try to INSERT a locked row; ON CONFLICT DO NOTHING if key already exists
            List<UUID> claimed = jdbc.query(
                    "INSERT INTO idempotency_keys "
                    + "(tenant_id, key, request_hash, response_status, expires_at, lock_status) "
                    + "VALUES (?, ?, ?, 0, ?, 'locked') "
                    + "ON CONFLICT DO NOTHING "
                    + "RETURNING id",
                    (rs, i) -> rs.getObject(1, UUID.class),
                    tenantId, key, requestHash, java.sql.Timestamp.from(expiresAt));

            if (!claimed.isEmpty()) {
                return ClaimResult.claimed(claimed.get(0));
            }

            // Key already exists — look it up
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, request_hash, lock_status, response_status, "
                    + "       response_body::text AS response_body, expires_at "
                    + "FROM idempotency_keys "
                    + "WHERE tenant_id IS NOT DISTINCT FROM ? AND key = ? AND expires_at > NOW()",
                    tenantId, key);

            if (rows.isEmpty()) {
                // Existing row is expired — delete it and claim fresh for this request
                jdbc.update(
                        "DELETE FROM idempotency_keys "
                        + "WHERE tenant_id IS NOT DISTINCT FROM ? AND key = ? AND expires_at <= NOW()",
                        tenantId, key);
                List<UUID> reClaimed = jdbc.query(
                        "INSERT INTO idempotency_keys "
                        + "(tenant_id, key, request_hash, response_status, expires_at, lock_status) "
                        + "VALUES (?, ?, ?, 0, ?, 'locked') "
                        + "ON CONFLICT DO NOTHING "
                        + "RETURNING id",
                        (rs, i) -> rs.getObject(1, UUID.class),
                        tenantId, key, requestHash, java.sql.Timestamp.from(expiresAt));
                return reClaimed.isEmpty() ? ClaimResult.concurrent() : ClaimResult.claimed(reClaimed.get(0));
            }

            Map<String, Object> existing = rows.get(0);
            String existingHash = (String) existing.get("request_hash");
            String lockStatus = (String) existing.get("lock_status");

            if (!requestHash.equals(existingHash)) {
                return ClaimResult.hashMismatch();
            }
            if ("locked".equals(lockStatus)) {
                return ClaimResult.concurrent();
            }
            return ClaimResult.replay(existing);

        } catch (Exception e) {
            log.error("Idempotency store error on claim key={}: {}", key, e.getMessage());
            return ClaimResult.storeError();
        }
    }

    // ---- execute and store ----

    private void executeAndStore(HttpServletRequest request,
                                 HttpServletResponse response,
                                 FilterChain chain,
                                 byte[] bodyBytes,
                                 UUID tenantId,
                                 String key,
                                 UUID rowId)
            throws ServletException, IOException {

        HttpServletRequest effectiveRequest = (bodyBytes != null)
                ? new RewindableRequestWrapper(request, bodyBytes)
                : request;
        var cachedResponse = new ContentCachingResponseWrapper(response);

        try {
            chain.doFilter(effectiveRequest, cachedResponse);
        } catch (Exception e) {
            // Business logic threw: mark row as failed by expiring it, then rethrow
            expireRow(rowId);
            throw e;
        }

        int status = cachedResponse.getStatus();

        // Only store 2xx responses; non-2xx may be transient errors
        if (status >= 200 && status < 300) {
            byte[] responseBody = cachedResponse.getContentAsByteArray();
            String contentType = cachedResponse.getContentType();
            boolean stored = storeResponse(rowId, status, responseBody, contentType);
            if (!stored) {
                // Fail closed: cannot guarantee idempotency for this request
                log.error("Failed to store idempotency response for key — returning 503");
                // Response has not been committed yet via copyBodyToResponse
                response.reset();
                writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "IDEMPOTENCY_STORE_UNAVAILABLE",
                        "Request succeeded but idempotency record could not be stored. Retry with the same key.");
                return;
            }
        } else {
            // Non-2xx: expire the locked row so the client can retry
            expireRow(rowId);
        }

        cachedResponse.copyBodyToResponse();
    }

    private boolean storeResponse(UUID rowId, int status, byte[] body, String contentType) {
        try {
            String bodyJson = body != null && body.length > 0
                    ? new String(body, StandardCharsets.UTF_8)
                    : null;
            int updated = jdbc.update(
                    "UPDATE idempotency_keys "
                    + "SET response_status = ?, response_body = ?::jsonb, "
                    + "    response_content_type = ?, lock_status = 'complete' "
                    + "WHERE id = ?",
                    status, bodyJson, contentType, rowId);
            return updated == 1;
        } catch (Exception e) {
            log.error("Failed to persist idempotency response for row {}: {}", rowId, e.getMessage());
            return false;
        }
    }

    private void expireRow(UUID rowId) {
        try {
            jdbc.update("UPDATE idempotency_keys SET expires_at = NOW() WHERE id = ?", rowId);
        } catch (Exception e) {
            log.debug("Could not expire idempotency row {}: {}", rowId, e.getMessage());
        }
    }

    // ---- replay ----

    private void replayResponse(Map<String, Object> cached, HttpServletResponse response)
            throws IOException {
        int status = ((Number) cached.get("response_status")).intValue();
        String body = (String) cached.get("response_body");
        String contentType = (String) cached.getOrDefault("response_content_type", "application/json");

        response.setStatus(status);
        response.setContentType(contentType != null ? contentType : "application/json");
        response.setHeader("X-Idempotent-Replayed", "true");
        if (body != null && !body.isBlank()) {
            response.getWriter().write(body);
        }
    }

    // ---- helpers ----

    private static byte[] readBodyBytes(HttpServletRequest request) throws IOException {
        InputStream is = request.getInputStream();
        return is.readAllBytes();
    }

    private static String hashRequest(byte[] body, String uri, String method, HttpServletRequest request) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(method.getBytes(StandardCharsets.UTF_8));
            md.update(uri.getBytes(StandardCharsets.UTF_8));
            if (body != null) {
                md.update(body);
            } else {
                String len = request.getHeader("Content-Length");
                if (len != null) {
                    md.update(len.getBytes(StandardCharsets.UTF_8));
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static boolean isMultipart(HttpServletRequest request) {
        String ct = request.getContentType();
        return ct != null && ct.toLowerCase().startsWith("multipart/");
    }

    private static void writeError(HttpServletResponse response, int status,
                                   String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }

    // ---- inner types ----

    private enum Outcome { CLAIMED, REPLAY, HASH_MISMATCH, CONCURRENT, STORE_ERROR }

    private record ClaimResult(Outcome outcome, UUID rowId, Map<String, Object> existing) {
        static ClaimResult claimed(UUID id)              { return new ClaimResult(Outcome.CLAIMED,     id,   null); }
        static ClaimResult replay(Map<String, Object> e) { return new ClaimResult(Outcome.REPLAY,      null, e);   }
        static ClaimResult hashMismatch()                { return new ClaimResult(Outcome.HASH_MISMATCH,null, null);}
        static ClaimResult concurrent()                  { return new ClaimResult(Outcome.CONCURRENT,  null, null);}
        static ClaimResult storeError()                  { return new ClaimResult(Outcome.STORE_ERROR, null, null);}
    }

    /** Re-serves a pre-read body bytes array as a request's input stream. */
    private static final class RewindableRequestWrapper extends HttpServletRequestWrapper {
        private final byte[] body;

        RewindableRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bais = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                public int read() { return bais.read(); }
                public boolean isFinished() { return bais.available() == 0; }
                public boolean isReady() { return true; }
                public void setReadListener(ReadListener l) {}
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
