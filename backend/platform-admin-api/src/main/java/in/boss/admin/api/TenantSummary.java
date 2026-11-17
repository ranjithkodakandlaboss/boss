package in.boss.admin.api;

import java.util.UUID;

public record TenantSummary(
        UUID id,
        String name,
        String slug,
        String status,
        String planTier,
        String industryType,
        String invitationStatus,
        String adminEmail,
        String workspaceUrl) {

    public TenantSummary(UUID id, String name, String slug, String status, String planTier, String industryType,
                         String invitationStatus, String adminEmail) {
        this(id, name, slug, status, planTier, industryType, invitationStatus, adminEmail, null);
    }

    public TenantSummary(UUID id, String name, String slug, String status, String planTier, String industryType) {
        this(id, name, slug, status, planTier, industryType, null, null, null);
    }
}
