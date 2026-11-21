package in.boss.identity.api;

import in.boss.identity.domain.CompanyLocationService;
import in.boss.identity.domain.CompanyWorkspaceService;
import in.boss.identity.domain.CustomerIdentityService;
import in.boss.shared.security.BossPermissions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final CompanyLocationService locations;

    public IdentityController(CustomerIdentityService identity, CompanyWorkspaceService company,
                              CompanyLocationService locations) {
        this.identity = identity;
        this.company = company;
        this.locations = locations;
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

    @PostMapping("/users")
    @PreAuthorize(BossPermissions.OWNER)
    public ResponseEntity<UserResponse> createUser(@RequestBody Map<String, String> request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(identity.createUser(
                request.get("email"), request.get("displayName"), request.get("role"), request.get("phone")));
    }

    @PutMapping("/users/{userId}")
    @PreAuthorize(BossPermissions.OWNER)
    public UserResponse updateUser(@PathVariable UUID userId, @RequestBody Map<String, String> request) {
        return identity.updateUser(userId, request.get("displayName"), request.get("role"),
                request.get("phone"), request.get("status"));
    }

    @DeleteMapping("/users/{userId}")
    @PreAuthorize(BossPermissions.OWNER)
    public ResponseEntity<Void> deleteUser(@PathVariable UUID userId) {
        identity.deleteUser(userId);
        return ResponseEntity.noContent().build();
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

    @GetMapping("/sites")
    public List<Map<String, Object>> listSites() {
        return locations.listSites();
    }

    @PostMapping("/company/sites")
    @PreAuthorize(BossPermissions.OWNER)
    public ResponseEntity<Map<String, Object>> addSite(@RequestBody Map<String, String> request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(locations.addSite(
                request.get("name"), request.get("code"), request.get("siteType")));
    }

    @PostMapping("/company/stores")
    @PreAuthorize(BossPermissions.OWNER)
    public ResponseEntity<Map<String, Object>> addStore(@RequestBody Map<String, String> request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(locations.addStore(
                request.get("siteCode"), request.get("name"), request.get("code"), request.get("storeType")));
    }

    @PutMapping("/company/sites/{siteId}")
    @PreAuthorize(BossPermissions.OWNER)
    public Map<String, Object> updateSite(@PathVariable UUID siteId, @RequestBody Map<String, String> request) {
        return locations.updateSite(siteId, request.get("name"), request.get("code"),
                request.get("siteType"), request.get("addressLine"), request.get("city"), request.get("state"));
    }

    @PutMapping("/company/stores/{storeId}")
    @PreAuthorize(BossPermissions.OWNER)
    public Map<String, Object> updateStore(@PathVariable UUID storeId, @RequestBody Map<String, String> request) {
        return locations.updateStore(storeId, request.get("name"), request.get("code"), request.get("storeType"));
    }

    @DeleteMapping("/company/stores/{storeId}")
    @PreAuthorize(BossPermissions.OWNER)
    public ResponseEntity<Void> deleteStore(@PathVariable UUID storeId) {
        locations.deleteStore(storeId);
        return ResponseEntity.noContent().build();
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
