package in.boss.admin.domain;

import in.boss.admin.api.TenantDetailsUpdateRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class TenantDetailsUpdateValidator {
    void validate(TenantDetailsUpdateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer details are required");
        }
        if (!hasUpdate(request)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "At least one editable customer detail is required");
        }
        if (request.tenantName() != null && request.tenantName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant name cannot be blank");
        }
        validateLegalEntityUpdates(request.legalEntities());
        validateSiteUpdates(request.sites());
    }

    private boolean hasUpdate(TenantDetailsUpdateRequest request) {
        return request.tenantName() != null
                || (request.legalEntities() != null && !request.legalEntities().isEmpty())
                || (request.sites() != null && !request.sites().isEmpty());
    }

    private void validateLegalEntityUpdates(List<TenantDetailsUpdateRequest.LegalEntityUpdate> entities) {
        if (entities == null) return;
        Set<UUID> entityIds = new HashSet<>();
        for (TenantDetailsUpdateRequest.LegalEntityUpdate entity : entities) {
            validateLegalEntityUpdate(entity, entityIds);
        }
    }

    private void validateLegalEntityUpdate(
            TenantDetailsUpdateRequest.LegalEntityUpdate entity, Set<UUID> entityIds) {
        if (entity == null || entity.id() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Legal entity ID is required");
        }
        if (!entityIds.add(entity.id())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Legal entity IDs must be unique");
        }
        if (entity.name() == null && entity.legalName() == null && entity.registeredAddress() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A legal entity field must be provided");
        }
        if (isBlankWhenProvided(entity.name()) || isBlankWhenProvided(entity.legalName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Legal entity names cannot be blank");
        }
    }

    private void validateSiteUpdates(List<TenantDetailsUpdateRequest.SiteUpdate> sites) {
        if (sites == null) return;
        Set<UUID> siteIds = new HashSet<>();
        for (TenantDetailsUpdateRequest.SiteUpdate site : sites) {
            validateSiteUpdate(site, siteIds);
        }
    }

    private void validateSiteUpdate(TenantDetailsUpdateRequest.SiteUpdate site, Set<UUID> siteIds) {
        if (site == null || site.id() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Site ID is required");
        }
        if (!siteIds.add(site.id())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Site IDs must be unique");
        }
        if (site.name() == null && site.address() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A site field must be provided");
        }
        if (isBlankWhenProvided(site.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Site name cannot be blank");
        }
    }

    private boolean isBlankWhenProvided(String value) {
        return value != null && value.isBlank();
    }
}
