package in.boss.admin.api;

import java.util.UUID;

public record ProvisionResult(UUID tenantId, UUID customerGroupId, UUID legalEntityId, UUID siteId,
                              boolean seedingApplied, String invitationStatus) { }
