package in.boss.admin.api;

import java.util.UUID;

public record TenantActivationResult(UUID tenantId, String tenantStatus, String invitationStatus) { }
