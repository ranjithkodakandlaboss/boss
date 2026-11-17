package in.boss.admin.api;

import java.time.Instant;
import java.util.UUID;

public record AdminInviteResult(UUID tenantId, UUID inviteId, String adminInviteToken, String invitationStatus,
                                Instant expiresAt) { }
