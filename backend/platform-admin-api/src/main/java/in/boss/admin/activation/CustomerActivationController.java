package in.boss.admin.activation;

import in.boss.admin.domain.AdminHttpResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    public CustomerActivationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
            boolean isExpired) {}

    public record CustomerActivationRequest(
            @NotBlank String token,
            @NotBlank String firebaseUid,
            String displayName,
            String phone) {}

    public record CustomerActivationResponse(
            boolean activated,
            UUID userId,
            UUID tenantId,
            String tenantSlug,
            String status) {}

    @GetMapping("/validate")
    public ResponseEntity<ValidateInviteResponse> validateToken(@RequestParam("token") String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token parameter is required");
        }
        String tokenHash = sha256(token.trim());

        List<ValidateInviteResponse> results = jdbc.query(
                "SELECT out_invite_id, out_tenant_id, out_tenant_name, out_tenant_slug, out_email, out_role, out_status, out_expires_at, out_is_expired " +
                        "FROM boss_validate_invite_token(?)",
                (rs, rowNum) -> new ValidateInviteResponse(
                        true,
                        rs.getObject("out_invite_id", UUID.class),
                        rs.getObject("out_tenant_id", UUID.class),
                        rs.getString("out_tenant_name"),
                        rs.getString("out_tenant_slug"),
                        rs.getString("out_email"),
                        rs.getString("out_role"),
                        rs.getString("out_status"),
                        rs.getTimestamp("out_expires_at"),
                        rs.getBoolean("out_is_expired")),
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

        return ResponseEntity.ok(response);
    }

    @PostMapping("/activate")
    public ResponseEntity<CustomerActivationResponse> activateAccount(@Valid @RequestBody CustomerActivationRequest request) {
        String tokenHash = sha256(request.token().trim());

        try {
            List<CustomerActivationResponse> results = jdbc.query(
                    "SELECT out_user_id, out_tenant_id, out_tenant_slug " +
                            "FROM boss_accept_owner_invite(?, ?, ?, ?)",
                    (rs, rowNum) -> new CustomerActivationResponse(
                            true,
                            rs.getObject("out_user_id", UUID.class),
                            rs.getObject("out_tenant_id", UUID.class),
                            rs.getString("out_tenant_slug"),
                            "active"),
                    tokenHash,
                    request.firebaseUid().trim(),
                    request.displayName() != null ? request.displayName().trim() : null,
                    request.phone() != null ? request.phone().trim() : null);

            if (results.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not activate account with the provided invitation");
            }

            return ResponseEntity.ok(results.getFirst());
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Activation failed";
            if (msg.contains("Invalid invitation token")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid invitation token");
            }
            if (msg.contains("already been")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, msg);
            }
            if (msg.contains("expired")) {
                throw new ResponseStatusException(HttpStatus.GONE, "Invitation token has expired");
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Account activation failed: " + msg);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
