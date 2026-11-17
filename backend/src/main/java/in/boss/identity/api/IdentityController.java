package in.boss.identity.api;

import in.boss.identity.domain.CompanyWorkspaceService;
import in.boss.identity.domain.CustomerIdentityService;
import in.boss.shared.security.BossPermissions;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
    private final CustomerIdentityService identity;
    private final CompanyWorkspaceService company;

    public IdentityController(CustomerIdentityService identity, CompanyWorkspaceService company) {
        this.identity = identity;
        this.company = company;
    }

    @PostMapping("/auth/activate")
    public ResponseEntity<UserResponse> activate(@RequestBody ActivateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(identity.activate(request));
    }

    @GetMapping("/auth/me")
    public UserResponse me() {
        return identity.currentUser();
    }

    @GetMapping("/tenant/info")
    public Map<String, Object> tenant() {
        return identity.tenantInfo();
    }

    @GetMapping("/users")
    @PreAuthorize(BossPermissions.OWNER)
    public Map<String, Object> users() {
        return identity.listUsers();
    }

    @PostMapping("/invites")
    @PreAuthorize(BossPermissions.OWNER)
    public ResponseEntity<Map<String, Object>> createInvite(@RequestBody InviteRequestBody request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(identity.createInvite(request));
    }

    @GetMapping("/invites")
    @PreAuthorize(BossPermissions.OWNER)
    public Object invites() {
        return identity.listInvites();
    }

    @PostMapping("/invites/{inviteId}/cancel")
    @PreAuthorize(BossPermissions.OWNER)
    public ResponseEntity<Void> cancelInvite(@PathVariable UUID inviteId) {
        identity.cancelInvite(inviteId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/company")
    public Map<String, Object> company() {
        return company.company();
    }

    @PutMapping("/company")
    @PreAuthorize(BossPermissions.OWNER)
    public Map<String, Object> saveCompany(@RequestBody CompanySaveRequest request) {
        return company.save(request, false);
    }

    @GetMapping("/onboarding")
    public Map<String, Object> onboarding() {
        return company.onboarding();
    }

    @PutMapping("/onboarding")
    @PreAuthorize(BossPermissions.OWNER)
    public Map<String, Object> saveOnboarding(@RequestBody CompanySaveRequest request) {
        return company.save(request, false);
    }

    @PostMapping("/onboarding/complete")
    @PreAuthorize(BossPermissions.OWNER)
    public Map<String, Object> completeOnboarding(@RequestBody CompanySaveRequest request) {
        return company.save(request, true);
    }
}
