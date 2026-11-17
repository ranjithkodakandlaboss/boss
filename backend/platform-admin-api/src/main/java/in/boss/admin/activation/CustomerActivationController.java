package in.boss.admin.activation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin/api/v1/invites")
public class CustomerActivationController {
    private final JdbcTemplate jdbc;
    private final String workspaceUrlTemplate;

    public CustomerActivationController(
            JdbcTemplate jdbc,
            @Value("${boss.admin.customer-workspace-url-template:${CUSTOMER_WORKSPACE_URL_TEMPLATE:}}") String workspaceUrlTemplate) {
        this.jdbc = jdbc;
        this.workspaceUrlTemplate = workspaceUrlTemplate;
    }

    public record ValidateInviteResponse(
            boolean valid,
            UUID inviteId,
            UUID tenantId,
            String tenantName,
            String tenantSlug,
            String email,
            String role,
            String status,
            Timestamp expiresAt,
            boolean isExpired,
            String workspaceUrl) {}

    public record CustomerActivationRequest(
            @NotBlank String token,
            @NotBlank String firebaseUid,
            String displayName,
            String phone,
            String tenantSlug) {}

    public record CustomerActivationResponse(
            boolean activated,
            UUID userId,
            UUID tenantId,
            String tenantSlug,
            String status,
            String workspaceUrl) {}

    @GetMapping("/validate")
    public ResponseEntity<ValidateInviteResponse> validateToken(
            @RequestParam("token") String token,
            @RequestParam(value = "tenant", required = false) String tenantSlug,
            @RequestParam(value = "slug", required = false) String slugParam) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token parameter is required");
        }
        String tokenHash = sha256(token.trim());

        List<ValidateInviteResponse> results = jdbc.query(
                "SELECT out_invite_id, out_tenant_id, out_tenant_name, out_tenant_slug, out_email, out_role, out_status, out_expires_at, out_is_expired " +
                        "FROM boss_validate_invite_token(?)",
                (rs, rowNum) -> {
                    String slug = rs.getString("out_tenant_slug");
                    return new ValidateInviteResponse(
                            true,
                            rs.getObject("out_invite_id", UUID.class),
                            rs.getObject("out_tenant_id", UUID.class),
                            rs.getString("out_tenant_name"),
                            slug,
                            rs.getString("out_email"),
                            rs.getString("out_role"),
                            rs.getString("out_status"),
                            rs.getTimestamp("out_expires_at"),
                            rs.getBoolean("out_is_expired"),
                            resolveWorkspaceUrl(slug));
                },
                tokenHash);

        if (results.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid invitation token");
        }

        ValidateInviteResponse response = results.getFirst();
        if (response.isExpired()) {
            throw new ResponseStatusException(HttpStatus.GONE, "Invitation token has expired");
        }
        if (!"pending".equals(response.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invitation token has already been " + response.status());
        }

        // Enforce tenant resolution isolation: if tenant slug was passed in URL, it MUST match token's tenant
        String expectedSlug = tenantSlug != null && !tenantSlug.isBlank() ? tenantSlug : slugParam;
        if (expectedSlug != null && !expectedSlug.isBlank() && !expectedSlug.equalsIgnoreCase(response.tenantSlug())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Invitation token does not belong to requested tenant workspace: " + expectedSlug);
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping("/activate")
    public ResponseEntity<CustomerActivationResponse> activateAccount(@Valid @RequestBody CustomerActivationRequest request) {
        String tokenHash = sha256(request.token().trim());

        // 1. Validate invite exists and is pending via SECURITY DEFINER function
        List<ValidateInviteResponse> results = jdbc.query(
                "SELECT out_invite_id, out_tenant_id, out_tenant_name, out_tenant_slug, out_email, out_role, out_status, out_expires_at, out_is_expired " +
                        "FROM boss_validate_invite_token(?)",
                (rs, rowNum) -> {
                    String slug = rs.getString("out_tenant_slug");
                    return new ValidateInviteResponse(
                            true,
                            rs.getObject("out_invite_id", UUID.class),
                            rs.getObject("out_tenant_id", UUID.class),
                            rs.getString("out_tenant_name"),
                            slug,
                            rs.getString("out_email"),
                            rs.getString("out_role"),
                            rs.getString("out_status"),
                            rs.getTimestamp("out_expires_at"),
                            rs.getBoolean("out_is_expired"),
                            resolveWorkspaceUrl(slug));
                },
                tokenHash);

        if (results.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid invitation token");
        }
        ValidateInviteResponse invite = results.getFirst();
        if (!"pending".equals(invite.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invitation token is already " + invite.status());
        }
        if (invite.isExpired()) {
            throw new ResponseStatusException(HttpStatus.GONE, "Invitation token has expired");
        }

        UUID tenantId = invite.tenantId();
        String actualSlug = invite.tenantSlug();

        // Validate workspace slug isolation if passed
        if (request.tenantSlug() != null && !request.tenantSlug().isBlank()
                && !request.tenantSlug().equalsIgnoreCase(actualSlug)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Invitation token does not belong to workspace: " + request.tenantSlug());
        }

        // 2. Accept invite using stored procedure
        List<UUID> userIds = jdbc.query(
                "SELECT out_user_id FROM boss_accept_owner_invite(?, ?, ?, ?)",
                (rs, rowNum) -> rs.getObject("out_user_id", UUID.class),
                tokenHash,
                request.firebaseUid(),
                request.displayName(),
                request.phone());

        if (userIds.isEmpty() || userIds.getFirst() == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Account activation failed to create user record");
        }

        return ResponseEntity.ok(new CustomerActivationResponse(
                true,
                userIds.getFirst(),
                tenantId,
                actualSlug,
                "active",
                resolveWorkspaceUrl(actualSlug)));
    }

    private String resolveWorkspaceUrl(String slug) {
        if (slug == null || slug.isBlank()) return null;
        if (workspaceUrlTemplate == null || workspaceUrlTemplate.isBlank() || !workspaceUrlTemplate.contains("{slug}")) {
            return null;
        }
        return workspaceUrlTemplate.replace("{slug}", slug);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
