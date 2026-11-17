package in.boss.admin.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.boss.admin.api.AdminInviteResult;
import in.boss.admin.api.ProvisionDependencyFailure;
import in.boss.admin.api.ProvisionResult;
import in.boss.admin.api.TenantPage;
import in.boss.admin.api.TenantProvisionRequest;
import in.boss.admin.api.TenantSummary;
import in.boss.admin.security.AdminPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TenantManagementService {
    public static final String NOT_REQUESTED = "NOT_REQUESTED";
    public static final String CREATED = "CREATED";
    public static final String RETRY_REQUIRED = "RETRY_REQUIRED";
    private static final Logger log = LoggerFactory.getLogger(TenantManagementService.class);
    private static final Set<String> STATUSES = Set.of("provisioning", "active", "suspended", "archived");
    private static final String PROVISION_PATH = "/admin/api/v1/tenants";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final TenantDefaultsSeed seeds;
    private final CustomerOwnerInvitePort invites;
    private final PlatformIdempotencyStore idempotency;
    private final MeterRegistry meters;
    private final String workspaceUrlTemplate;

    public TenantManagementService(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager txManager,
                                   TenantDefaultsSeed seeds, CustomerOwnerInvitePort invites,
                                   PlatformIdempotencyStore idempotency, MeterRegistry meters,
                                   @Value("${boss.admin.customer-workspace-url-template:${CUSTOMER_WORKSPACE_URL_TEMPLATE:https://boss-customer-web-dev-201664309080.asia-south1.run.app/?tenant={slug}}}") String workspaceUrlTemplate) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(txManager);
        this.seeds = seeds;
        this.invites = invites;
        this.idempotency = idempotency;
        this.meters = meters;
        this.workspaceUrlTemplate = workspaceUrlTemplate;
    }

    public String resolveWorkspaceUrl(String slug) {
        if (slug == null || slug.isBlank()) return null;
        if (workspaceUrlTemplate != null && !workspaceUrlTemplate.isBlank()) {
            return workspaceUrlTemplate.replace("{slug}", slug);
        }
        return "https://boss-customer-web-dev-201664309080.asia-south1.run.app/?tenant=" + slug;
    }

    public AdminHttpResult provision(TenantProvisionRequest request, String idempotencyKey,
                                     AdminPrincipal actor, String ipAddress) {
        validateKey(idempotencyKey);
        String groupSlug = groupSlug(request);
        if (groupSlug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer group slug is required");
        }
        String tenantSlug = tenantSlug(request);
        if (tenantSlug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant slug is required");
        }

        // Validate slug formatting: lowercase alphanumeric with single hyphens, 2-63 chars
        if (!tenantSlug.matches("^[a-z0-9]+(?:-[a-z0-9]+)*$") || tenantSlug.length() < 2 || tenantSlug.length() > 63) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid tenant slug format: " + tenantSlug);
        }
        if (!groupSlug.matches("^[a-z0-9]+(?:-[a-z0-9]+)*$") || groupSlug.length() < 2 || groupSlug.length() > 63) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid customer group slug format: " + groupSlug);
        }

        PlatformIdempotencyStore.Claim claim = idempotency.claim(idempotencyKey, provisionHash(request));
        if (claim instanceof PlatformIdempotencyStore.Replay replay) {
            return replay(replay);
        }
        UUID lockId = ((PlatformIdempotencyStore.Proceed) claim).rowId();
        CreatedTenant created;
        try {
            created = transactions.execute(status -> createTenant(request, groupSlug, tenantSlug, actor, ipAddress));
        } catch (RuntimeException exception) {
            idempotency.expire(lockId);
            if (isSlugConflict(exception)) {
                meters.counter("boss.admin.tenant.provision", "result", "conflict").increment();
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant or customer group slug already exists");
            }
            throw exception;
        }
        if (created == null) {
            idempotency.expire(lockId);
            throw new IllegalStateException("Tenant provisioning did not complete");
        }
        try {
            return finishInvitation(created, request.adminEmail(), actor, ipAddress, lockId);
        } catch (RuntimeException exception) {
            idempotency.expire(lockId);
            throw exception;
        }
    }

    public TenantPage list(String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0 and size must be 1..100");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown tenant status");
        }
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM tenant_registry WHERE (?::text IS NULL OR status = ?)",
                Integer.class, status, status);
        List<TenantSummary> content = jdbc.query(
                "SELECT t.id, t.name, t.slug, t.status, t.plan_tier, c.industry_type, " +
                        "inv.out_status AS invite_status, inv.out_email AS invite_email " +
                        "FROM tenant_registry t " +
                        "LEFT JOIN tenant_onboarding_config c ON c.tenant_id = t.id " +
                        "LEFT JOIN LATERAL boss_get_tenant_invitation_summary(t.id) inv ON true " +
                        "WHERE (?::text IS NULL OR t.status = ?) " +
                        "ORDER BY t.created_at DESC, t.id LIMIT ? OFFSET ?",
                (rs, row) -> {
                    String slug = rs.getString("slug");
                    return new TenantSummary(
                            rs.getObject("id", UUID.class), rs.getString("name"), slug,
                            rs.getString("status"), rs.getString("plan_tier"), rs.getString("industry_type"),
                            rs.getString("invite_status"), rs.getString("invite_email"),
                            resolveWorkspaceUrl(slug));
                },
                status, status, size, page * size);
        return new TenantPage(content, page, total == null ? 0 : total);
    }

    public TenantSummary resolveTenantBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slug is required");
        }
        List<TenantSummary> results = jdbc.query(
                "SELECT t.id, t.name, t.slug, t.status, t.plan_tier, c.industry_type, " +
                        "inv.out_status AS invite_status, inv.out_email AS invite_email " +
                        "FROM tenant_registry t " +
                        "LEFT JOIN tenant_onboarding_config c ON c.tenant_id = t.id " +
                        "LEFT JOIN LATERAL boss_get_tenant_invitation_summary(t.id) inv ON true " +
                        "WHERE t.slug = ?",
                (rs, row) -> {
                    String rowSlug = rs.getString("slug");
                    return new TenantSummary(
                            rs.getObject("id", UUID.class), rs.getString("name"), rowSlug,
                            rs.getString("status"), rs.getString("plan_tier"), rs.getString("industry_type"),
                            rs.getString("invite_status"), rs.getString("invite_email"),
                            resolveWorkspaceUrl(rowSlug));
                },
                slug.trim().toLowerCase());
        if (results.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant workspace not found for slug: " + slug);
        }
        return results.getFirst();
    }

    @Transactional
    public void activate(UUID tenantId, AdminPrincipal actor, String ipAddress) {
        setTenantContext(tenantId);
        String oldStatus = lockTenantStatus(tenantId);
        if (!"provisioning".equals(oldStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a provisioning tenant can be activated");
        }
        String industryType = onboardingIndustry(tenantId);
        if (!seeds.isComplete(tenantId, industryType)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Industry seeding is incomplete");
        }
        if (invitationOutstanding(tenantId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Initial administrator invitation must succeed before activation");
        }
        jdbc.update("UPDATE tenant_registry SET status = 'active', updated_at = now() WHERE id = ?", tenantId);
        audit(actor, "tenant.activated", tenantId, Map.of("status", oldStatus), Map.of("status", "active"), ipAddress);
        log.info("Tenant activated tenantId={}", tenantId);
        meters.counter("boss.admin.tenant.lifecycle", "action", "activate").increment();
    }

    @Transactional
    public void suspend(UUID tenantId, String reason, AdminPrincipal actor, String ipAddress) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is required");
        }
        setTenantContext(tenantId);
        String oldStatus = lockTenantStatus(tenantId);
        if ("suspended".equals(oldStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant is already suspended");
        }
        if (!"active".equals(oldStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an active tenant can be suspended");
        }
        jdbc.update("UPDATE tenant_registry SET status = 'suspended', updated_at = now() WHERE id = ?", tenantId);
        audit(actor, "tenant.suspended", tenantId, Map.of("status", oldStatus),
                Map.of("status", "suspended", "reason", reason), ipAddress);
        log.info("Tenant suspended tenantId={}", tenantId);
        meters.counter("boss.admin.tenant.lifecycle", "action", "suspend").increment();
    }

    @Transactional
    public void reactivate(UUID tenantId, AdminPrincipal actor, String ipAddress) {
        setTenantContext(tenantId);
        String oldStatus = lockTenantStatus(tenantId);
        if (!"suspended".equals(oldStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a suspended tenant can be reactivated");
        }
        jdbc.update("UPDATE tenant_registry SET status = 'active', updated_at = now() WHERE id = ?", tenantId);
        audit(actor, "tenant.reactivated", tenantId, Map.of("status", oldStatus), Map.of("status", "active"), ipAddress);
        log.info("Tenant reactivated tenantId={}", tenantId);
        meters.counter("boss.admin.tenant.lifecycle", "action", "reactivate").increment();
    }

    public AdminHttpResult retryInvite(UUID tenantId, String email, String idempotencyKey,
                                       AdminPrincipal actor, String ipAddress) {
        validateKey(idempotencyKey);
        String hash = PlatformIdempotencyStore.requestHash("POST",
                PROVISION_PATH + "/" + tenantId + "/admin-invite", jsonBytes(Map.of("email", email)));
        PlatformIdempotencyStore.Claim claim = idempotency.claim(idempotencyKey, hash);
        if (claim instanceof PlatformIdempotencyStore.Replay replay) return replay(replay);
        UUID lockId = ((PlatformIdempotencyStore.Proceed) claim).rowId();
        try {
            requireTenant(tenantId);
            if (inviteCreated(tenantId)) {
                AdminHttpResult conflict = stored(lockId, HttpStatus.CONFLICT.value(),
                        Map.of("error", "INVITE_ALREADY_CREATED",
                                "message", "An initial administrator invitation was already created"));
                return conflict;
            }
            CustomerOwnerInvitePort.OwnerInvite invite = invites.inviteInitialOwner(tenantId, email);
            transactions.executeWithoutResult(status -> audit(actor, "tenant.admin_invite_created", tenantId, null,
                    Map.of("inviteId", invite.inviteId().toString(), "invitationStatus", CREATED), ipAddress));
            AdminInviteResult body = new AdminInviteResult(tenantId, invite.inviteId(), invite.oneTimeToken(),
                    CREATED, invite.expiresAt());
            log.info("Initial administrator invitation created tenantId={} inviteId={}", tenantId, invite.inviteId());
            meters.counter("boss.admin.tenant.invite", "result", "created").increment();
            return stored(lockId, HttpStatus.CREATED.value(), body);
        } catch (IdentityInviteUnavailableException exception) {
            idempotency.expire(lockId);
            transactions.executeWithoutResult(status -> audit(actor, "tenant.admin_invite_retry_required", tenantId,
                    null, Map.of("invitationStatus", RETRY_REQUIRED, "error", exception.getMessage()), ipAddress));
            meters.counter("boss.admin.tenant.invite", "result", "unavailable").increment();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_INVITE_UNAVAILABLE: " + exception.getMessage());
        } catch (RuntimeException exception) {
            idempotency.expire(lockId);
            throw exception;
        }
    }

    private AdminHttpResult finishInvitation(CreatedTenant created, String email, AdminPrincipal actor,
                                             String ipAddress, UUID lockId) {
        ProvisionResult createdWithoutInvite = new ProvisionResult(created.tenantId(), created.customerGroupId(),
                created.legalEntityId(), created.siteId(), null, true, NOT_REQUESTED);
        if (email == null || email.isBlank()) {
            log.info("Tenant provisioned tenantId={} invitationStatus={}", created.tenantId(), NOT_REQUESTED);
            meters.counter("boss.admin.tenant.provision", "result", "created").increment();
            return stored(lockId, HttpStatus.CREATED.value(), createdWithoutInvite);
        }
        try {
            CustomerOwnerInvitePort.OwnerInvite invite = invites.inviteInitialOwner(created.tenantId(), email);
            transactions.executeWithoutResult(status -> audit(actor, "tenant.admin_invite_created", created.tenantId(),
                    null, Map.of("inviteId", invite.inviteId().toString(), "invitationStatus", CREATED), ipAddress));
            ProvisionResult body = new ProvisionResult(created.tenantId(), created.customerGroupId(),
                    created.legalEntityId(), created.siteId(), invite.oneTimeToken(), true, CREATED);
            log.info("Tenant provisioned tenantId={} invitationStatus={}", created.tenantId(), CREATED);
            meters.counter("boss.admin.tenant.provision", "result", "created").increment();
            return stored(lockId, HttpStatus.CREATED.value(), body);
        } catch (IdentityInviteUnavailableException exception) {
            String errorReason = exception.getMessage() != null ? exception.getMessage() : "Email delivery unconfigured";
            transactions.executeWithoutResult(status -> audit(actor, "tenant.admin_invite_retry_required",
                    created.tenantId(), null, Map.of("invitationStatus", RETRY_REQUIRED, "error", errorReason), ipAddress));
            ProvisionDependencyFailure body = new ProvisionDependencyFailure(
                    "ADMIN_INVITE_RETRY_REQUIRED",
                    "The tenant was created, but the initial administrator invitation was not delivered: " + errorReason +
                            ". Retry POST /admin/api/v1/tenants/{tenantId}/admin-invite.",
                    created.tenantId(), created.customerGroupId(), created.legalEntityId(), created.siteId(),
                    true, RETRY_REQUIRED);
            log.info("Tenant provisioned tenantId={} invitationStatus={}", created.tenantId(), RETRY_REQUIRED);
            meters.counter("boss.admin.tenant.provision", "result", "invite_retry").increment();
            return stored(lockId, HttpStatus.FAILED_DEPENDENCY.value(), body);
        }
    }

    private CreatedTenant createTenant(TenantProvisionRequest request, String groupSlug, String tenantSlug,
                                       AdminPrincipal actor, String ipAddress) {
        UUID groupId = Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO customer_groups(name, slug) VALUES (?, ?) RETURNING id",
                UUID.class, request.customerGroupName(), groupSlug));
        UUID tenantId = UUID.randomUUID();
        setTenantContext(tenantId);
        jdbc.update("INSERT INTO tenant_registry(id, customer_group_id, name, slug, status, plan_tier) " +
                        "VALUES (?, ?, ?, ?, 'provisioning', ?)",
                tenantId, groupId, request.tenantName(), tenantSlug, request.planTier());

        String regAddrJson = null;
        if (request.registeredAddress() != null && !request.registeredAddress().isBlank()) {
            try {
                regAddrJson = mapper.writeValueAsString(Map.of("address", request.registeredAddress().trim()));
            } catch (JsonProcessingException ignored) {}
        }
        UUID legalEntityId = Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO legal_entities(tenant_id, name, legal_name, pan, entity_type, registered_address) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb) RETURNING id",
                UUID.class, tenantId, request.legalEntityName(), request.legalEntityName(),
                request.pan(), request.entityType(), regAddrJson));

        if (request.gstin() != null && !request.gstin().isBlank()) {
            jdbc.queryForObject(
                    "SELECT boss_register_gstin(?, ?, ?, ?)",
                    UUID.class, tenantId, legalEntityId, request.gstin().trim().toUpperCase(), request.legalEntityName());
        }

        String siteAddrJson = null;
        if (request.siteAddress() != null && !request.siteAddress().isBlank()) {
            try {
                siteAddrJson = mapper.writeValueAsString(Map.of("address", request.siteAddress().trim()));
            } catch (JsonProcessingException ignored) {}
        }
        UUID siteId = Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO physical_sites(tenant_id, legal_entity_id, name, code, site_type, address) " +
                        "VALUES (?, ?, ?, ?, 'factory', ?::jsonb) RETURNING id",
                UUID.class, tenantId, legalEntityId, request.siteName(), request.siteCode(), siteAddrJson));
        seeds.apply(tenantId, request.industryType());
        boolean emailPresent = request.adminEmail() != null && !request.adminEmail().isBlank();
        audit(actor, "tenant.provisioned", tenantId, null, Map.of(
                "tenantId", tenantId.toString(),
                "customerGroupId", groupId.toString(),
                "legalEntityId", legalEntityId.toString(),
                "siteId", siteId.toString(),
                "status", "provisioning",
                "slug", tenantSlug,
                "industryType", request.industryType(),
                "seedingApplied", true,
                "adminEmailPresent", emailPresent), ipAddress);
        return new CreatedTenant(tenantId, groupId, legalEntityId, siteId);
    }

    private AdminHttpResult stored(UUID lockId, int status, Object body) {
        idempotency.complete(lockId, status, storedJson(body));
        return new AdminHttpResult(status, body, false);
    }

    private AdminHttpResult replay(PlatformIdempotencyStore.Replay replay) {
        try {
            Object body = replay.body() == null ? mapper.createObjectNode() : mapper.readTree(replay.body());
            return new AdminHttpResult(replay.status(), body, true);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored idempotent response is not valid JSON", exception);
        }
    }

    private boolean invitationOutstanding(UUID tenantId) {
        List<Boolean> emailRequested = jdbc.query(
                "SELECT new_data->>'adminEmailPresent' FROM platform_audit_events " +
                        "WHERE entity_id = ? AND action = 'tenant.provisioned' ORDER BY created_at DESC LIMIT 1",
                (rs, row) -> Boolean.valueOf(rs.getString(1)), tenantId);
        return !emailRequested.isEmpty() && Boolean.TRUE.equals(emailRequested.getFirst())
                && !inviteCreated(tenantId);
    }

    private boolean inviteCreated(UUID tenantId) {
        List<String> statuses = jdbc.query(
                "SELECT new_data->>'invitationStatus' FROM platform_audit_events " +
                        "WHERE entity_id = ? AND action = 'tenant.admin_invite_created' ORDER BY created_at DESC LIMIT 1",
                (rs, row) -> rs.getString(1), tenantId);
        return !statuses.isEmpty() && CREATED.equals(statuses.getFirst());
    }

    private String lockTenantStatus(UUID tenantId) {
        List<String> statuses = jdbc.query(
                "SELECT status FROM tenant_registry WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), tenantId);
        if (statuses.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant was not found");
        }
        return statuses.getFirst();
    }

    private String onboardingIndustry(UUID tenantId) {
        List<String> industries = jdbc.query(
                "SELECT industry_type FROM tenant_onboarding_config WHERE tenant_id = ?",
                (rs, row) -> rs.getString(1), tenantId);
        if (industries.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant onboarding configuration was not found");
        }
        return industries.getFirst();
    }

    private void requireTenant(UUID tenantId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM tenant_registry WHERE id = ?", Integer.class, tenantId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant was not found");
        }
    }

    private void setTenantContext(UUID tenantId) {
        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (PreparedStatement ps = con.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
                ps.setString(1, tenantId.toString());
                ps.execute();
            }
            return null;
        });
    }

    private void audit(AdminPrincipal actor, String action, UUID entityId, Map<String, Object> oldData,
                       Map<String, Object> newData, String ipAddress) {
        jdbc.update("INSERT INTO platform_audit_events(admin_id, action, entity_type, entity_id, " +
                        "old_data, new_data, ip_address) VALUES (?, ?, 'tenant', ?, ?::jsonb, ?::jsonb, ?::inet)",
                actor == null ? null : actor.adminId(), action, entityId, json(oldData), json(newData), ipAddress);
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize audit data", exception);
        }
    }

    private String storedJson(Object body) {
        ObjectNode node = mapper.valueToTree(body);
        node.remove("adminInviteToken");
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize idempotent response", exception);
        }
    }

    private byte[] jsonBytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not hash request", exception);
        }
    }

    private String provisionHash(TenantProvisionRequest request) {
        return PlatformIdempotencyStore.requestHash("POST", PROVISION_PATH, jsonBytes(request));
    }

    private void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY");
        }
        if (idempotencyKey.length() > PlatformIdempotencyStore.MAX_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        }
    }

    private boolean isSlugConflict(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof DuplicateKeyException) return true;
            current = current.getCause();
        }
        return false;
    }

    private String groupSlug(TenantProvisionRequest request) {
        if (request.customerGroupSlug() != null && !request.customerGroupSlug().isBlank()) {
            return request.customerGroupSlug().trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        }
        return request.customerGroupName().trim().toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private String tenantSlug(TenantProvisionRequest request) {
        if (request.tenantSlug() != null && !request.tenantSlug().isBlank()) {
            return request.tenantSlug().trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        }
        if (request.tenantName() != null && !request.tenantName().isBlank()) {
            return request.tenantName().trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        }
        return groupSlug(request);
    }

    private record CreatedTenant(UUID tenantId, UUID customerGroupId, UUID legalEntityId, UUID siteId) { }
}
