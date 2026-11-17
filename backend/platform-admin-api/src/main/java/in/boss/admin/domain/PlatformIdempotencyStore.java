package in.boss.admin.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Platform-scoped idempotency using the shared {@code idempotency_keys} table.
 * Keys are stored with {@code tenant_id} null, which V066 makes unique.
 * Only this service's claim and completion rows are visible when no tenant
 * context is set, because the table policy hides tenant-scoped keys.
 */
@Component
public class PlatformIdempotencyStore {
    private static final Logger log = LoggerFactory.getLogger(PlatformIdempotencyStore.class);
    public static final int MAX_KEY_LENGTH = 128;
    private static final int TTL_HOURS = 24;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    public PlatformIdempotencyStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    public static String requestHash(String method, String path, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(method.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(body == null ? new byte[0] : body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public Claim claim(String key, String requestHash) {
        try {
            return requiresNew.execute(status -> claimWithinTransaction(key, requestHash));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "IDEMPOTENCY_STORE_UNAVAILABLE");
        }
    }

    public void complete(UUID rowId, int statusCode, String body) {
        requiresNew.executeWithoutResult(status -> jdbc.update(
                "UPDATE idempotency_keys SET response_status = ?, response_body = ?::jsonb, " +
                        "response_content_type = 'application/json', lock_status = 'complete' WHERE id = ?",
                statusCode, body, rowId));
    }

    public void expire(UUID rowId) {
        try {
            requiresNew.executeWithoutResult(status ->
                    jdbc.update("UPDATE idempotency_keys SET expires_at = NOW() WHERE id = ?", rowId));
        } catch (DataAccessException exception) {
            log.warn("Could not expire an idempotency lock");
        }
    }

    private Claim claimWithinTransaction(String key, String requestHash) {
        Instant expiresAt = Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS);
        UUID claimed = insertLock(key, requestHash, expiresAt);
        if (claimed != null) return new Proceed(claimed);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, request_hash, lock_status, response_status, response_body::text AS response_body " +
                        "FROM idempotency_keys WHERE tenant_id IS NULL AND key = ? AND expires_at > NOW()",
                key);
        if (rows.isEmpty()) {
            jdbc.update("DELETE FROM idempotency_keys WHERE tenant_id IS NULL AND key = ? AND expires_at <= NOW()", key);
            UUID reclaimed = insertLock(key, requestHash, expiresAt);
            if (reclaimed == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CONCURRENT_REQUEST");
            }
            return new Proceed(reclaimed);
        }

        Map<String, Object> existing = rows.getFirst();
        if (!requestHash.equals(existing.get("request_hash"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSE");
        }
        if (!"complete".equals(existing.get("lock_status"))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CONCURRENT_REQUEST");
        }
        int responseStatus = ((Number) existing.get("response_status")).intValue();
        if (responseStatus < 200) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CONCURRENT_REQUEST");
        }
        Object body = existing.get("response_body");
        return new Replay(responseStatus, body == null ? null : body.toString());
    }

    private UUID insertLock(String key, String requestHash, Instant expiresAt) {
        List<UUID> claimed = jdbc.query(
                "INSERT INTO idempotency_keys (tenant_id, key, request_hash, response_status, expires_at, lock_status) " +
                        "VALUES (NULL, ?, ?, 0, ?, 'locked') ON CONFLICT DO NOTHING RETURNING id",
                (rs, row) -> rs.getObject(1, UUID.class),
                key, requestHash, java.sql.Timestamp.from(expiresAt));
        return claimed.isEmpty() ? null : claimed.getFirst();
    }

    public sealed interface Claim permits Proceed, Replay { }

    public record Proceed(UUID rowId) implements Claim { }

    public record Replay(int status, String body) implements Claim { }
}
