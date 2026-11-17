package in.boss.admin.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TenantProvisionRequest(
        @NotBlank String customerGroupName,
        String customerGroupSlug,
        @NotBlank String tenantName,
        @NotBlank String tenantSlug,
        String planTier,
        @NotBlank String legalEntityName,
        @NotBlank @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]$") String pan,
        @NotBlank @Pattern(regexp = "^(PRIVATE_LIMITED|LLP|SOLE_PROPRIETORSHIP|PARTNERSHIP|PUBLIC_LIMITED|HUF|OTHER)$") String entityType,
        String gstin,
        String registeredAddress,
        @NotBlank String siteCode,
        @NotBlank String siteName,
        String siteAddress,
        String adminFullName,
        @Email String adminEmail,
        String adminPhone,
        @NotBlank @Pattern(regexp = "^(FABRICATION_STEEL|PRECISION_MACHINING|ELECTRONICS_ASSEMBLY|FORGING|CASTING|GENERAL_MANUFACTURING)$") String industryType) {

    public TenantProvisionRequest {
        if (planTier == null || planTier.isBlank()) planTier = "pilot";
    }
}
