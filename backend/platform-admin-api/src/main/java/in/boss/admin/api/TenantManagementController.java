package in.boss.admin.api;

import in.boss.admin.domain.TenantManagementService;
import in.boss.admin.security.AdminPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@Validated
@RequestMapping("/admin/api/v1")
public class TenantManagementController {
    private final TenantManagementService service;

    public TenantManagementController(TenantManagementService service) {
        this.service = service;
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public ProvisionResult provision(@Valid @RequestBody TenantProvisionRequest request,
                                     @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                                     Authentication authentication,
                                     jakarta.servlet.http.HttpServletRequest httpRequest) {
        return service.provision(request, (AdminPrincipal) authentication.getPrincipal(), clientIp(httpRequest));
    }

    @PostMapping("/tenants/{tenantId}/suspend")
    public Map<String, String> suspend(@PathVariable UUID tenantId, @Valid @RequestBody SuspendRequest request,
                                       Authentication authentication,
                                       jakarta.servlet.http.HttpServletRequest httpRequest) {
        service.suspend(tenantId, request.reason(), (AdminPrincipal) authentication.getPrincipal(), clientIp(httpRequest));
        return Map.of("status", "suspended");
    }

    @PostMapping("/tenants/{tenantId}/reactivate")
    public Map<String, String> reactivate(@PathVariable UUID tenantId, Authentication authentication,
                                          jakarta.servlet.http.HttpServletRequest httpRequest) {
        service.reactivate(tenantId, (AdminPrincipal) authentication.getPrincipal(), clientIp(httpRequest));
        return Map.of("status", "active");
    }

    private String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    public record SuspendRequest(@NotBlank String reason) { }
}
