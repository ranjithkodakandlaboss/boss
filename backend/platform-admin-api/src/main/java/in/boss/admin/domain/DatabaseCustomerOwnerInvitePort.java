package in.boss.admin.domain;

import in.boss.admin.email.ResendEmailSender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Primary
public class DatabaseCustomerOwnerInvitePort implements CustomerOwnerInvitePort {
    private static final Logger log = LoggerFactory.getLogger(DatabaseCustomerOwnerInvitePort.class);

    private final JdbcTemplate jdbc;
    private final ResendEmailSender emailSender;
    private final String invitationUrlBase;
    private final SecureRandom secureRandom = new SecureRandom();

    public DatabaseCustomerOwnerInvitePort(
            JdbcTemplate jdbc,
            ResendEmailSender emailSender,
            @Value("${boss.email.invitation-url-base:${INVITATION_URL_BASE:http://localhost:5173/activate}}") String invitationUrlBase) {
        this.jdbc = jdbc;
        this.emailSender = emailSender;
        this.invitationUrlBase = invitationUrlBase;
    }

    @Override
    public OwnerInvite inviteInitialOwner(UUID tenantId, String email) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String oneTimeToken = HexFormat.of().formatHex(bytes);
        String tokenHash = sha256(oneTimeToken);
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

        // 1. Create invite in database via SECURITY DEFINER function
        UUID inviteId = jdbc.queryForObject(
                "SELECT boss_create_owner_invite(?, ?, ?, ?)",
                UUID.class, tenantId, email, tokenHash, Timestamp.from(expiresAt));

        // 2. Fetch tenant name for email
        String tenantName = jdbc.queryForObject(
                "SELECT name FROM tenant_registry WHERE id = ?",
                String.class, tenantId);

        // 3. Send email via Resend
        String activationLink = invitationUrlBase + "?token=" + oneTimeToken;
        var sendResult = emailSender.sendInvitationEmail(email, tenantName == null ? "Your Organization" : tenantName, activationLink, expiresAt);

        if (!sendResult.delivered()) {
            log.warn("Resend email delivery not completed for tenantId={}: {}", tenantId, sendResult.errorMessage());
            throw new IdentityInviteUnavailableException(sendResult.errorMessage());
        }

        log.info("Invitation created and sent via Resend inviteId={} tenantId={}", inviteId, tenantId);
        return new OwnerInvite(inviteId, oneTimeToken, expiresAt);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
