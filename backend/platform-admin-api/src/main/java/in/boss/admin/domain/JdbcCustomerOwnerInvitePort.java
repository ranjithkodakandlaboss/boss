package in.boss.admin.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the initial OWNER invite in the customer identity table.
 * The plaintext token is returned once to the Platform Admin caller.
 * It is not logged. Email delivery is not configured in this service.
 */
@Component
public class JdbcCustomerOwnerInvitePort implements CustomerOwnerInvitePort {
    static final int TTL_DAYS = 7;

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public JdbcCustomerOwnerInvitePort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public OwnerInvite inviteInitialOwner(UUID tenantId, String email) {
        String token = newToken();
        String hash = sha256(token);
        Instant expiresAt = Instant.now().plus(TTL_DAYS, ChronoUnit.DAYS);
        UUID inviteId = jdbc.queryForObject(
                "SELECT boss_create_owner_invite(?, ?, ?, ?)",
                UUID.class, tenantId, email.trim(), hash, Timestamp.from(expiresAt));
        if (inviteId == null) throw new IllegalStateException("Invite was not created");
        return new OwnerInvite(inviteId, token, expiresAt);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash invite token", exception);
        }
    }
}
