package in.boss.admin.api;

import java.util.UUID;

public record ProvisionResult(UUID tenantId, UUID customerGroupId, UUID legalEntityId, UUID siteId,
                              String adminInviteToken, boolean seedingApplied, String invitationStatus) { }
