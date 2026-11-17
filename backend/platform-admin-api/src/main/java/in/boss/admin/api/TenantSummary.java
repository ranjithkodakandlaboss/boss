package in.boss.admin.api;

import java.util.UUID;

public record TenantSummary(UUID id, String name, String slug, String status, String planTier, String industryType) { }
