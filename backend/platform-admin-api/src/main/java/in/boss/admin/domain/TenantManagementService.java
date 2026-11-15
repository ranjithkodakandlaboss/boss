package in.boss.admin.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.admin.api.PagedTenants;
import in.boss.admin.api.ProvisionResult;
import in.boss.admin.api.TenantProvisionRequest;
import in.boss.admin.api.TenantSummary;
import in.boss.admin.security.AdminPrincipal;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TenantManagementService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TenantManagementService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public ProvisionResult provision(TenantProvisionRequest request, AdminPrincipal actor, String ipAddress) {
        try {
            UUID groupId = jdbc.queryForObject(
                    "INSERT INTO customer_groups(name, slug) VALUES (?, ?) RETURNING id",
                    UUID.class, request.customerGroupName(), groupSlug(request));
            UUID tenantId = jdbc.queryForObject(
                    "INSERT INTO tenant_registry(customer_group_id, name, slug, status, plan_tier) " +
                            "VALUES (?, ?, ?, 'provisioning', ?) RETURNING id",
                    UUID.class, groupId, request.tenantName(), request.tenantSlug(), request.planTier());

            setTenantContext(tenantId);
            UUID legalEntityId = jdbc.queryForObject(
                    "INSERT INTO legal_entities(tenant_id, name, legal_name, pan, entity_type) " +
                            "VALUES (?, ?, ?, ?, ?) RETURNING id",
                    UUID.class, tenantId, request.legalEntityName(), request.legalEntityName(),
                    request.pan(), request.entityType());
            UUID siteId = jdbc.queryForObject(
                    "INSERT INTO physical_sites(tenant_id, legal_entity_id, name, code, site_type) " +
                            "VALUES (?, ?, ?, ?, 'factory') RETURNING id",
                    UUID.class, tenantId, legalEntityId, request.siteName(), request.siteCode());
            jdbc.update("INSERT INTO tenant_onboarding_config(tenant_id, industry_type) VALUES (?, ?)",
                    tenantId, request.industryType());

            audit(actor, "tenant.provisioned", "tenant_registry", tenantId, null,
                    Map.of("tenantId", tenantId, "customerGroupId", groupId,
                            "legalEntityId", legalEntityId, "siteId", siteId,
                            "status", "provisioning", "industryType", request.industryType()), ipAddress);
            // No invite token is minted until the approved identity contract defines a
            // durable customer-user invite flow. No industry seed function exists yet.
            return new ProvisionResult(tenantId, groupId, legalEntityId, siteId, null, false);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant or customer group slug already exists");
        }
    }

    @Transactional(readOnly = true)
    public PagedTenants list(String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0 and size must be 1..100");
        }
        if (status != null && !List.of("provisioning", "active", "suspended", "archived").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported tenant status");
        }
        String where = status == null ? "" : " WHERE t.status = ?";
        Object[] countArgs = status == null ? new Object[]{} : new Object[]{status};
        long total = jdbc.queryForObject("SELECT count(*) FROM tenant_registry t" + where,
                Long.class, countArgs);
        String sql = "SELECT t.id, t.name, t.slug, t.status, t.plan_tier " +
                "FROM tenant_registry t" + where +
                " ORDER BY t.created_at DESC LIMIT ? OFFSET ?";
        Object[] args = status == null ? new Object[]{size, (long) page * size}
                : new Object[]{status, size, (long) page * size};
        List<TenantSummary> tenants = jdbc.query(sql, (rs, row) -> new TenantSummary(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("slug"),
                rs.getString("status"), rs.getString("plan_tier"), null), args);
        List<TenantSummary> content = tenants.stream().map(tenant -> {
            setTenantContext(tenant.id());
            List<String> industries = jdbc.query("SELECT industry_type FROM tenant_onboarding_config WHERE tenant_id = ?",
                    (rs, row) -> rs.getString(1), tenant.id());
            return new TenantSummary(tenant.id(), tenant.name(), tenant.slug(), tenant.status(), tenant.planTier(),
                    industries.isEmpty() ? null : industries.getFirst());
        }).toList();
        return new PagedTenants(content, page, total);
    }

    @Transactional
    public void suspend(UUID tenantId, String reason, AdminPrincipal actor, String ipAddress) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is required");
        }
        String oldStatus = lockTenantStatus(tenantId);
        if ("suspended".equals(oldStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant is already suspended");
        }
        jdbc.update("UPDATE tenant_registry SET status = 'suspended', updated_at = now() WHERE id = ?", tenantId);
        audit(actor, "tenant.suspended", "tenant_registry", tenantId,
                Map.of("status", oldStatus), Map.of("status", "suspended", "reason", reason), ipAddress);
    }

    @Transactional
    public void reactivate(UUID tenantId, AdminPrincipal actor, String ipAddress) {
        String oldStatus = lockTenantStatus(tenantId);
        if (!"suspended".equals(oldStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a suspended tenant can be reactivated");
        }
        jdbc.update("UPDATE tenant_registry SET status = 'active', updated_at = now() WHERE id = ?", tenantId);
        audit(actor, "tenant.reactivated", "tenant_registry", tenantId,
                Map.of("status", oldStatus), Map.of("status", "active"), ipAddress);
    }

    private String lockTenantStatus(UUID tenantId) {
        List<String> statuses = jdbc.query("SELECT status FROM tenant_registry WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), tenantId);
        if (statuses.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        return statuses.getFirst();
    }

    private void setTenantContext(UUID tenantId) {
        jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
                statement.setString(1, tenantId.toString());
                statement.execute();
                return true;
            }
        });
    }

    private void audit(AdminPrincipal actor, String action, String entityType, UUID entityId,
                       Map<String, Object> oldData, Map<String, Object> newData, String ipAddress) {
        try {
            jdbc.update("INSERT INTO platform_audit_events(admin_id, entity_type, entity_id, action, old_data, new_data, ip_address) " +
                            "VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::inet)",
                    actor.adminId(), entityType, entityId, action, json(oldData), json(newData), ipAddress);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize platform audit event", exception);
        }
    }

    private String json(Map<String, Object> data) throws JsonProcessingException {
        return data == null ? null : mapper.writeValueAsString(data);
    }

    private String groupSlug(TenantProvisionRequest request) {
        if (request.customerGroupSlug() != null && !request.customerGroupSlug().isBlank()) {
            return request.customerGroupSlug();
        }
        return request.customerGroupName().trim().toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
