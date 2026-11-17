package in.boss.admin.api;

import java.util.List;
import java.util.UUID;

public record TenantDetails(
        UUID tenantId,
        UUID customerGroupId,
        String customerGroupName,
        String customerGroupSlug,
        String tenantName,
        String tenantSlug,
        String tenantStatus,
        String planTier,
        String industryType,
        String invitationStatus,
        String adminEmail,
        String workspaceUrl,
        List<LegalEntityDetails> legalEntities,
        List<SiteDetails> sites) {

    public record LegalEntityDetails(
            UUID id,
            String name,
            String legalName,
            String pan,
            String entityType,
            String registeredAddress,
            String status,
            List<GstRegistrationDetails> gstRegistrations) { }

    public record GstRegistrationDetails(
            UUID id,
            String gstin,
            String tradeName,
            String registrationType,
            String stateCode,
            String status,
            java.time.LocalDate effectiveFrom,
            java.time.LocalDate effectiveTo) { }

    public record SiteDetails(
            UUID id,
            UUID legalEntityId,
            UUID gstRegistrationId,
            String name,
            String code,
            String siteType,
            String address,
            String status) { }
}
