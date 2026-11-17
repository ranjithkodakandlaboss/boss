package in.boss.admin.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.admin.api.TenantDetails;
import in.boss.admin.api.TenantDetailsUpdateRequest;
import in.boss.admin.api.TenantDetailsUpdateResult;
import in.boss.admin.security.AdminPrincipal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TenantDetailsService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final PlatformIdempotencyStore idempotency;
    private final TenantManagementSupport support;
    private final TenantDetailsUpdateValidator validator;

    public TenantDetailsService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager txManager,
            PlatformIdempotencyStore idempotency,
            TenantManagementSupport support,
            TenantDetailsUpdateValidator validator) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(txManager);
        this.idempotency = idempotency;
        this.support = support;
        this.validator = validator;
    }

    public TenantDetails details(UUID tenantId) {
        return transactions.execute(status -> {
            support.setTenantContext(tenantId);
            return loadTenantDetails(tenantId);
        });
    }

    public AdminHttpResult updateDetails(UUID tenantId, TenantDetailsUpdateRequest request, String idempotencyKey,
                                         AdminPrincipal actor, String ipAddress) {
        support.validateKey(idempotencyKey);
        validator.validate(request);
        String path = TenantManagementService.PROVISION_PATH + "/" + tenantId + "/details";
        String hash = PlatformIdempotencyStore.requestHash("PATCH", path, support.jsonBytes(request));
        PlatformIdempotencyStore.Claim claim = idempotency.claim(idempotencyKey, hash);
        if (claim instanceof PlatformIdempotencyStore.Replay replay) return support.replay(replay);
        UUID lockId = ((PlatformIdempotencyStore.Proceed) claim).rowId();

        try {
            TenantDetailsUpdateResult result = transactions.execute(
                    status -> applyDetailsUpdate(tenantId, request, actor, ipAddress));
            if (result == null) throw new IllegalStateException("Customer detail update did not complete");
            return support.stored(lockId, HttpStatus.OK.value(), result);
        } catch (RuntimeException exception) {
            idempotency.expire(lockId);
            throw exception;
        }
    }

    private TenantDetailsUpdateResult applyDetailsUpdate(
            UUID tenantId, TenantDetailsUpdateRequest request, AdminPrincipal actor, String ipAddress) {
        support.setTenantContext(tenantId);
        String tenantStatus = support.lockTenantStatus(tenantId);
        if ("archived".equals(tenantStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Archived customer details cannot be edited");
        }

        List<String> updatedFields = new ArrayList<>();
        updateTenantName(tenantId, request.tenantName(), updatedFields);
        updateLegalEntities(tenantId, request.legalEntities(), updatedFields);
        updateSites(tenantId, request.sites(), updatedFields);
        support.audit(actor, "tenant.details_updated", tenantId, null,
                Map.of("updatedFields", updatedFields), ipAddress);
        return new TenantDetailsUpdateResult(tenantId, List.copyOf(updatedFields));
    }

    private void updateTenantName(UUID tenantId, String name, List<String> updatedFields) {
        if (name == null) return;
        jdbc.update("UPDATE tenant_registry SET name = ?, updated_at = now() WHERE id = ?", name.trim(), tenantId);
        updatedFields.add("tenantName");
    }

    private void updateLegalEntities(
            UUID tenantId, List<TenantDetailsUpdateRequest.LegalEntityUpdate> entities, List<String> updatedFields) {
        if (entities == null) return;
        for (TenantDetailsUpdateRequest.LegalEntityUpdate entity : entities) {
            if (updateLegalEntity(tenantId, entity) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Legal entity was not found in this tenant");
            }
            updatedFields.add("legalEntity:" + entity.id());
        }
    }

    private void updateSites(
            UUID tenantId, List<TenantDetailsUpdateRequest.SiteUpdate> sites, List<String> updatedFields) {
        if (sites == null) return;
        for (TenantDetailsUpdateRequest.SiteUpdate site : sites) {
            if (updateSite(tenantId, site) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Site was not found in this tenant");
            }
            updatedFields.add("site:" + site.id());
        }
    }

    private TenantDetails loadTenantDetails(UUID tenantId) {
        List<TenantDetails> tenants = jdbc.query(
                "SELECT t.id, t.customer_group_id, cg.name AS customer_group_name, cg.slug AS customer_group_slug, " +
                        "t.name, t.slug, t.status, t.plan_tier, c.industry_type, " +
                        "inv.out_status AS invite_status, inv.out_email AS invite_email, " +
                        "inv.out_expires_at AS invite_expires_at, delivery.invitation_status AS delivery_status, " +
                        "t.initial_admin_email " +
                        "FROM tenant_registry t " +
                        "JOIN customer_groups cg ON cg.id = t.customer_group_id " +
                        "LEFT JOIN tenant_onboarding_config c ON c.tenant_id = t.id " +
                        "LEFT JOIN LATERAL boss_get_tenant_invitation_summary(t.id) inv ON true " +
                        "LEFT JOIN LATERAL (" +
                        "  SELECT new_data->>'invitationStatus' AS invitation_status " +
                        "  FROM platform_audit_events " +
                        "  WHERE entity_id = t.id " +
                        "    AND action IN ('tenant.admin_invite_created', 'tenant.admin_invite_retry_required') " +
                        "  ORDER BY created_at DESC, id DESC LIMIT 1" +
                        ") delivery ON true " +
                        "WHERE t.id = ?",
                (rs, row) -> {
                    String email = rs.getString("invite_email");
                    if (email == null) email = rs.getString("initial_admin_email");
                    return new TenantDetails(
                            rs.getObject("id", UUID.class),
                            rs.getObject("customer_group_id", UUID.class),
                            rs.getString("customer_group_name"),
                            rs.getString("customer_group_slug"),
                            rs.getString("name"),
                            rs.getString("slug"),
                            rs.getString("status"),
                            rs.getString("plan_tier"),
                            rs.getString("industry_type"),
                            support.invitationStatus(rs.getString("status"), rs.getString("invite_status"),
                                    rs.getTimestamp("invite_expires_at"), rs.getString("delivery_status"), email),
                            email,
                            support.resolveWorkspaceUrl(rs.getString("slug")),
                            List.of(),
                            List.of());
                },
                tenantId);
        if (tenants.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant was not found");
        }

        List<TenantDetails.LegalEntityDetails> entities = jdbc.query(
                "SELECT id, name, legal_name, pan, entity_type, registered_address::text AS address, status " +
                        "FROM legal_entities WHERE tenant_id = ? ORDER BY id",
                (rs, row) -> new TenantDetails.LegalEntityDetails(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("legal_name"),
                        rs.getString("pan"),
                        rs.getString("entity_type"),
                        addressValue(rs.getString("address")),
                        rs.getString("status"),
                        List.of()),
                tenantId);
        Map<UUID, List<TenantDetails.GstRegistrationDetails>> registrationsByEntity = new HashMap<>();
        jdbc.query(
                "SELECT id, legal_entity_id, gstin, trade_name, registration_type, state_code, status, " +
                        "effective_from, effective_to FROM gst_registrations " +
                        "WHERE tenant_id = ? ORDER BY legal_entity_id, id",
                rs -> {
                    UUID legalEntityId = rs.getObject("legal_entity_id", UUID.class);
                    registrationsByEntity.computeIfAbsent(legalEntityId, ignored -> new ArrayList<>())
                            .add(new TenantDetails.GstRegistrationDetails(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("gstin"),
                                    rs.getString("trade_name"),
                                    rs.getString("registration_type"),
                                    rs.getString("state_code"),
                                    rs.getString("status"),
                                    rs.getObject("effective_from", java.time.LocalDate.class),
                                    rs.getObject("effective_to", java.time.LocalDate.class)));
                },
                tenantId);
        entities = entities.stream()
                .map(entity -> new TenantDetails.LegalEntityDetails(
                        entity.id(), entity.name(), entity.legalName(), entity.pan(), entity.entityType(),
                        entity.registeredAddress(), entity.status(),
                        registrationsByEntity.getOrDefault(entity.id(), List.of())))
                .toList();
        List<TenantDetails.SiteDetails> sites = jdbc.query(
                "SELECT id, legal_entity_id, gst_reg_id, name, code, site_type, address::text AS address, status " +
                        "FROM physical_sites WHERE tenant_id = ? ORDER BY id",
                (rs, row) -> new TenantDetails.SiteDetails(
                        rs.getObject("id", UUID.class),
                        rs.getObject("legal_entity_id", UUID.class),
                        rs.getObject("gst_reg_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("code"),
                        rs.getString("site_type"),
                        addressValue(rs.getString("address")),
                        rs.getString("status")),
                tenantId);

        TenantDetails tenant = tenants.getFirst();
        return new TenantDetails(tenant.tenantId(), tenant.customerGroupId(), tenant.customerGroupName(),
                tenant.customerGroupSlug(), tenant.tenantName(), tenant.tenantSlug(), tenant.tenantStatus(),
                tenant.planTier(), tenant.industryType(), tenant.invitationStatus(), tenant.adminEmail(),
                tenant.workspaceUrl(), entities, sites);
    }

    private int updateLegalEntity(UUID tenantId, TenantDetailsUpdateRequest.LegalEntityUpdate update) {
        List<String> assignments = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        if (update.name() != null) {
            assignments.add("name = ?");
            parameters.add(update.name().trim());
        }
        if (update.legalName() != null) {
            assignments.add("legal_name = ?");
            parameters.add(update.legalName().trim());
        }
        if (update.registeredAddress() != null) {
            assignments.add("registered_address = ?::jsonb");
            parameters.add(addressJson(update.registeredAddress()));
        }
        assignments.add("updated_at = now()");
        parameters.add(update.id());
        parameters.add(tenantId);
        return jdbc.update("UPDATE legal_entities SET " + String.join(", ", assignments)
                + " WHERE id = ? AND tenant_id = ?", parameters.toArray());
    }

    private int updateSite(UUID tenantId, TenantDetailsUpdateRequest.SiteUpdate update) {
        List<String> assignments = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        if (update.name() != null) {
            assignments.add("name = ?");
            parameters.add(update.name().trim());
        }
        if (update.address() != null) {
            assignments.add("address = ?::jsonb");
            parameters.add(addressJson(update.address()));
        }
        assignments.add("updated_at = now()");
        parameters.add(update.id());
        parameters.add(tenantId);
        return jdbc.update("UPDATE physical_sites SET " + String.join(", ", assignments)
                + " WHERE id = ? AND tenant_id = ?", parameters.toArray());
    }

    private String addressJson(String address) {
        return address.isBlank() ? null : support.json(Map.of("address", address.trim()));
    }

    private String addressValue(String addressJson) {
        if (addressJson == null || addressJson.isBlank()) return null;
        try {
            JsonNode value = mapper.readTree(addressJson);
            if (value.isTextual()) return value.asText();
            String simpleAddress = value.path("address").asText(null);
            if (simpleAddress != null) return simpleAddress;
            List<String> parts = List.of(
                    value.path("line1").asText(""),
                    value.path("city").asText(""),
                    value.path("state").asText(""));
            String formatted = String.join(", ", parts.stream().filter(part -> !part.isBlank()).toList());
            return formatted.isBlank() ? addressJson : formatted;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored customer address is not valid JSON", exception);
        }
    }
}
