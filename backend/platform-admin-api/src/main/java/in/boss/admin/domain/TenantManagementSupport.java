package in.boss.admin.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.boss.admin.security.AdminPrincipal;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class TenantManagementSupport {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PlatformIdempotencyStore idempotency;
    private final String workspaceUrlTemplate;

    TenantManagementSupport(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformIdempotencyStore idempotency,
            @Value("${boss.admin.customer-workspace-url-template:${CUSTOMER_WORKSPACE_URL_TEMPLATE:}}")
            String workspaceUrlTemplate) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.idempotency = idempotency;
        this.workspaceUrlTemplate = workspaceUrlTemplate;
    }

    String resolveWorkspaceUrl(String slug) {
        if (slug == null || slug.isBlank()) return null;
        if (workspaceUrlTemplate == null || workspaceUrlTemplate.isBlank() || !workspaceUrlTemplate.contains("{slug}")) {
            return null;
        }
        return workspaceUrlTemplate.replace("{slug}", slug);
    }

    String invitationStatus(String tenantStatus, String inviteStatus, java.sql.Timestamp expiresAt,
                            String deliveryStatus, String adminEmail) {
        if ("accepted".equalsIgnoreCase(inviteStatus)) return "ACCEPTED";
        if ("cancelled".equalsIgnoreCase(inviteStatus)) return "CANCELLED";
        if ("expired".equalsIgnoreCase(inviteStatus)
                || (expiresAt != null && !expiresAt.toInstant().isAfter(java.time.Instant.now()))) {
            return "EXPIRED";
        }
        if (deliveryStatus != null && !deliveryStatus.isBlank()) return deliveryStatus;
        if ("pending".equalsIgnoreCase(inviteStatus)) return "PENDING";
        if (hasEmail(adminEmail)) {
            return "provisioning".equalsIgnoreCase(tenantStatus)
                    ? TenantManagementService.PENDING_ACTIVATION
                    : TenantManagementService.RETRY_REQUIRED;
        }
        return TenantManagementService.NOT_REQUESTED;
    }

    boolean hasEmail(String email) {
        return email != null && !email.isBlank();
    }

    void setTenantContext(UUID tenantId) {
        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (PreparedStatement ps = con.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
                ps.setString(1, tenantId.toString());
                ps.execute();
            }
            return null;
        });
    }

    String lockTenantStatus(UUID tenantId) {
        List<String> statuses = jdbc.query(
                "SELECT status FROM tenant_registry WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), tenantId);
        if (statuses.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant was not found");
        }
        return statuses.getFirst();
    }

    void audit(AdminPrincipal actor, String action, UUID entityId, Map<String, Object> oldData,
               Map<String, Object> newData, String ipAddress) {
        jdbc.update("INSERT INTO platform_audit_events(admin_id, action, entity_type, entity_id, " +
                        "old_data, new_data, ip_address) VALUES (?, ?, 'tenant', ?, ?::jsonb, ?::jsonb, ?::inet)",
                actor == null ? null : actor.adminId(), action, entityId, json(oldData), json(newData), ipAddress);
    }

    String json(Object value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize value", exception);
        }
    }

    AdminHttpResult stored(UUID lockId, int status, Object body) {
        idempotency.complete(lockId, status, storedJson(body));
        return new AdminHttpResult(status, body, false);
    }

    AdminHttpResult replay(PlatformIdempotencyStore.Replay replay) {
        try {
            Object body = replay.body() == null ? mapper.createObjectNode() : mapper.readTree(replay.body());
            return new AdminHttpResult(replay.status(), body, true);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored idempotent response is not valid JSON", exception);
        }
    }

    String storedJson(Object body) {
        ObjectNode node = mapper.valueToTree(body);
        node.remove("adminInviteToken");
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize idempotent response", exception);
        }
    }

    byte[] jsonBytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not hash request", exception);
        }
    }

    void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY");
        }
        if (idempotencyKey.length() > PlatformIdempotencyStore.MAX_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        }
    }
}
