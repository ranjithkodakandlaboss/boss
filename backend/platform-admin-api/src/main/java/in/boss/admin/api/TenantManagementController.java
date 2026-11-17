package in.boss.admin.api;

import in.boss.admin.domain.AdminHttpResult;
import in.boss.admin.domain.TenantDetailsService;
import in.boss.admin.domain.TenantManagementService;
import in.boss.admin.security.AdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/admin/api/v1")
public class TenantManagementController {
    private final TenantManagementService service;
    private final TenantDetailsService detailsService;

    public TenantManagementController(TenantManagementService service, TenantDetailsService detailsService) {
        this.service = service;
        this.detailsService = detailsService;
    }

    @PostMapping("/tenants")
    public ResponseEntity<Object> provision(@Valid @RequestBody TenantProvisionRequest request,
                                            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                                            Authentication authentication, HttpServletRequest httpRequest) {
        return respond(service.provision(request, idempotencyKey, principal(authentication), clientIp(httpRequest)));
    }

    @GetMapping("/tenants")
    public TenantPage list(@RequestParam(required = false) String status,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "50") int size) {
        return service.list(status, page, size);
    }

    @GetMapping("/tenants/{tenantId}")
    public TenantDetails details(@PathVariable UUID tenantId) {
        return detailsService.details(tenantId);
    }

    @PatchMapping("/tenants/{tenantId}/details")
    public ResponseEntity<Object> updateDetails(
            @PathVariable UUID tenantId,
            @Valid @RequestBody TenantDetailsUpdateRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        return respond(detailsService.updateDetails(tenantId, request, idempotencyKey,
                principal(authentication), clientIp(httpRequest)));
    }

    @GetMapping("/tenants/resolve")
    public TenantSummary resolve(@RequestParam("slug") String slug) {
        return service.resolveTenantBySlug(slug);
    }

    @GetMapping("/tenants/by-slug/{slug}")
    public TenantSummary bySlug(@PathVariable("slug") String slug) {
        return service.resolveTenantBySlug(slug);
    }

    @PostMapping("/tenants/{tenantId}/activate")
    public TenantActivationResult activate(@PathVariable UUID tenantId, Authentication authentication,
                                           HttpServletRequest httpRequest) {
        return service.activate(tenantId, principal(authentication), clientIp(httpRequest));
    }

    @PostMapping("/tenants/{tenantId}/suspend")
    public Map<String, String> suspend(@PathVariable UUID tenantId, @Valid @RequestBody SuspendRequest request,
                                       Authentication authentication, HttpServletRequest httpRequest) {
        service.suspend(tenantId, request.reason(), principal(authentication), clientIp(httpRequest));
        return Map.of("status", "suspended");
    }

    @PostMapping("/tenants/{tenantId}/reactivate")
    public Map<String, String> reactivate(@PathVariable UUID tenantId, Authentication authentication,
                                          HttpServletRequest httpRequest) {
        service.reactivate(tenantId, principal(authentication), clientIp(httpRequest));
        return Map.of("status", "active");
    }

    @PostMapping("/tenants/{tenantId}/admin-invite")
    public ResponseEntity<Object> retryInvite(@PathVariable UUID tenantId, @Valid @RequestBody AdminInviteRequest request,
                                              @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                                              Authentication authentication, HttpServletRequest httpRequest) {
        return respond(service.retryInvite(tenantId, request.email(), idempotencyKey,
                principal(authentication), clientIp(httpRequest)));
    }

    private ResponseEntity<Object> respond(AdminHttpResult result) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status());
        if (result.replayed()) response.header("X-Idempotent-Replayed", "true");
        return response.body(result.body());
    }

    private AdminPrincipal principal(Authentication authentication) {
        return (AdminPrincipal) authentication.getPrincipal();
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null || ip.isBlank() ? null : ip;
    }

    public record SuspendRequest(@NotBlank String reason) { }
}
