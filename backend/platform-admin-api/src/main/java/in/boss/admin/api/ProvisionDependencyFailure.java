package in.boss.admin.api;

import java.util.UUID;

public record ProvisionDependencyFailure(String error, String message, UUID tenantId, UUID customerGroupId,
                                         UUID legalEntityId, UUID siteId, boolean seedingApplied,
                                         String invitationStatus) { }
