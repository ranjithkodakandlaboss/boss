package in.boss.admin.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record TenantDetailsUpdateRequest(
        @Size(max = 120) String tenantName,
        List<@Valid LegalEntityUpdate> legalEntities,
        List<@Valid SiteUpdate> sites) {

    public record LegalEntityUpdate(
            @NotNull UUID id,
            @Size(max = 160) String name,
            @Size(max = 200) String legalName,
            @Size(max = 1000) String registeredAddress) { }

    public record SiteUpdate(
            @NotNull UUID id,
            @Size(max = 120) String name,
            @Size(max = 1000) String address) { }
}
