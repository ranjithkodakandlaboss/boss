package in.boss.admin.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Boundary for the customer identity service. Platform Admin must not write
 * {@code app_users} or {@code invite_tokens}, and must not mint a token itself.
 * The implementation has to use the customer Firebase project and the OWNER role.
 */
public interface CustomerOwnerInvitePort {
    OwnerInvite inviteInitialOwner(UUID tenantId, String email);

    record OwnerInvite(UUID inviteId, String oneTimeToken, Instant expiresAt) { }
}
