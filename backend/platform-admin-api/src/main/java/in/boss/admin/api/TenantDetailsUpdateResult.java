package in.boss.admin.api;

import java.util.List;
import java.util.UUID;

public record TenantDetailsUpdateResult(UUID tenantId, List<String> updatedFields) { }
