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
import java.util.List;
import java.util.Map;
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
    private final String workspaceUrlTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public DatabaseCustomerOwnerInvitePort(
            JdbcTemplate jdbc,
            ResendEmailSender emailSender,
            @Value("${boss.email.invitation-url-base:${INVITATION_URL_BASE:https://boss-customer-web-dev-201664309080.asia-south1.run.app/activate}}") String invitationUrlBase,
            @Value("${boss.admin.customer-workspace-url-template:${CUSTOMER_WORKSPACE_URL_TEMPLATE:https://boss-customer-web-dev-201664309080.asia-south1.run.app/?tenant={slug}}}") String workspaceUrlTemplate) {
        this.jdbc = jdbc;
        this.emailSender = emailSender;
        this.invitationUrlBase = invitationUrlBase;
        this.workspaceUrlTemplate = workspaceUrlTemplate;
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

        // 2. Fetch tenant name and slug for email and tenant-specific workspace URL
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name, slug FROM tenant_registry WHERE id = ?", tenantId);
        String tenantName = rows.isEmpty() || rows.getFirst().get("name") == null
                ? "Your Organization" : rows.getFirst().get("name").toString();
        String tenantSlug = rows.isEmpty() || rows.getFirst().get("slug") == null
                ? "" : rows.getFirst().get("slug").toString();

        // 3. Format tenant-specific workspace URL and activation link
        String workspaceUrl = resolveWorkspaceUrl(tenantSlug);
        String activationLink = resolveActivationLink(tenantSlug, oneTimeToken);

        // 4. Send email via Resend
        var sendResult = emailSender.sendInvitationEmail(email, tenantName, workspaceUrl, activationLink, expiresAt);

        if (!sendResult.delivered()) {
            log.warn("Resend email delivery not completed for tenantId={}: {}", tenantId, sendResult.errorMessage());
            throw new IdentityInviteUnavailableException(sendResult.errorMessage());
        }

        log.info("Invitation created and sent via Resend inviteId={} tenantId={} slug={}", inviteId, tenantId, tenantSlug);
        return new OwnerInvite(inviteId, oneTimeToken, expiresAt);
    }

    private String resolveWorkspaceUrl(String slug) {
        if (slug == null || slug.isBlank()) return null;
        if (workspaceUrlTemplate != null && !workspaceUrlTemplate.isBlank()) {
            return workspaceUrlTemplate.replace("{slug}", slug);
        }
        return "https://boss-customer-web-dev-201664309080.asia-south1.run.app/?tenant=" + slug;
    }

    private String resolveActivationLink(String slug, String token) {
        if (invitationUrlBase.contains("{slug}")) {
            return invitationUrlBase.replace("{slug}", slug) + "?token=" + token;
        }
        String separator = invitationUrlBase.contains("?") ? "&" : "?";
        if (slug != null && !slug.isBlank()) {
            return invitationUrlBase + separator + "tenant=" + slug + "&token=" + token;
        }
        return invitationUrlBase + separator + "token=" + token;
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
