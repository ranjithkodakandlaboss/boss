package in.boss.admin.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.admin.api.AdminInviteResult;
import in.boss.admin.api.ProvisionResult;
import in.boss.admin.api.TenantActivationResult;
import in.boss.admin.api.TenantPage;
import in.boss.admin.api.TenantProvisionRequest;
import in.boss.admin.api.TenantSummary;
import in.boss.admin.security.AdminPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TenantManagementService {
    public static final String NOT_REQUESTED = "NOT_REQUESTED";
    public static final String PENDING_ACTIVATION = "PENDING_ACTIVATION";
    public static final String CREATED = "CREATED";
    public static final String RETRY_REQUIRED = "RETRY_REQUIRED";
    private static final Logger log = LoggerFactory.getLogger(TenantManagementService.class);
    private static final Set<String> STATUSES = Set.of("provisioning", "active", "suspended", "archived");
    static final String PROVISION_PATH = "/admin/api/v1/tenants";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final TenantDefaultsSeed seeds;
    private final CustomerOwnerInvitePort invites;
    private final PlatformIdempotencyStore idempotency;
    private final MeterRegistry meters;
    private final TenantManagementSupport support;

    public TenantManagementService(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager txManager,
                                   TenantDefaultsSeed seeds, CustomerOwnerInvitePort invites,
                                   PlatformIdempotencyStore idempotency, MeterRegistry meters,
                                   TenantManagementSupport support) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(txManager);
        this.seeds = seeds;
        this.invites = invites;
        this.idempotency = idempotency;
        this.meters = meters;
        this.support = support;
    }

    public AdminHttpResult provision(TenantProvisionRequest request, String idempotencyKey,
                                     AdminPrincipal actor, String ipAddress) {
        support.validateKey(idempotencyKey);
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
            return support.replay(replay);
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
        String invitationStatus = support.hasEmail(request.adminEmail()) ? PENDING_ACTIVATION : NOT_REQUESTED;
        ProvisionResult result = new ProvisionResult(created.tenantId(), created.customerGroupId(),
                created.legalEntityId(), created.siteId(), true, invitationStatus);
        log.info("Tenant provisioned tenantId={} invitationStatus={}", created.tenantId(), invitationStatus);
        meters.counter("boss.admin.tenant.provision", "result", "created").increment();
        try {
            return support.stored(lockId, HttpStatus.CREATED.value(), result);
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
                "SELECT count(*) FROM tenant_registry " +
                        "WHERE (?::text IS NULL OR status = ?)",
                Integer.class, status, status);
        List<TenantSummary> content = jdbc.query(
                "SELECT t.id, t.name, t.slug, t.status, t.plan_tier, c.industry_type, " +
                        "inv.out_status AS invite_status, inv.out_email AS invite_email, inv.out_expires_at AS invite_expires_at, " +
                        "delivery.invitation_status AS delivery_status, t.initial_admin_email " +
                        "FROM tenant_registry t " +
                        "LEFT JOIN tenant_onboarding_config c ON c.tenant_id = t.id " +
                        "LEFT JOIN LATERAL boss_get_tenant_invitation_summary(t.id) inv ON true " +
                        "LEFT JOIN LATERAL (" +
                        "  SELECT new_data->>'invitationStatus' AS invitation_status " +
                        "  FROM platform_audit_events " +
                        "  WHERE entity_id = t.id " +
                        "    AND action IN ('tenant.admin_invite_created', 'tenant.admin_invite_retry_required') " +
                        "  ORDER BY created_at DESC, id DESC LIMIT 1" +
                        ") delivery ON true " +
                        "WHERE (?::text IS NULL OR t.status = ?) " +
                        "ORDER BY t.created_at DESC, t.id LIMIT ? OFFSET ?",
                (rs, row) -> {
                    String slug = rs.getString("slug");
                    String adminEmail = rs.getString("invite_email");
                    if (adminEmail == null) adminEmail = rs.getString("initial_admin_email");
                    return new TenantSummary(
                            rs.getObject("id", UUID.class), rs.getString("name"), slug,
                            rs.getString("status"), rs.getString("plan_tier"), rs.getString("industry_type"),
                            support.invitationStatus(rs.getString("status"), rs.getString("invite_status"),
                                    rs.getTimestamp("invite_expires_at"), rs.getString("delivery_status"), adminEmail),
                            adminEmail,
                            support.resolveWorkspaceUrl(slug));
                },
                status, status, size, page * size);
        return new TenantPage(content, page, total == null ? 0 : total);
    }

    public TenantSummary resolveTenantBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slug is required");
        }
        List<TenantSummary> results = jdbc.query(
                "SELECT t.id, t.name, t.slug, t.status FROM tenant_registry t WHERE t.slug = ?",
                (rs, row) -> new TenantSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("slug"),
                        rs.getString("status"),
                        null,
                        null,
                        null,
                        null,
                        support.resolveWorkspaceUrl(rs.getString("slug"))),
                slug.trim().toLowerCase());
        if (results.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant workspace not found for slug: " + slug);
        }
        return results.getFirst();
    }

    public TenantActivationResult activate(UUID tenantId, AdminPrincipal actor, String ipAddress) {
        String adminEmail = transactions.execute(status -> {
            support.setTenantContext(tenantId);
            String oldStatus = support.lockTenantStatus(tenantId);
            if (!"provisioning".equals(oldStatus)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a provisioning tenant can be activated");
            }
            String industryType = onboardingIndustry(tenantId);
            if (!seeds.isComplete(tenantId, industryType)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Industry seeding is incomplete");
            }
            jdbc.update("UPDATE tenant_registry SET status = 'active', updated_at = now() WHERE id = ?", tenantId);
            support.audit(actor, "tenant.activated", tenantId, Map.of("status", oldStatus),
                    Map.of("status", "active"), ipAddress);
            return jdbc.queryForObject(
                    "SELECT initial_admin_email FROM tenant_registry WHERE id = ?", String.class, tenantId);
        });
        if (adminEmail == null || adminEmail.isBlank()) {
            log.info("Tenant activated tenantId={} invitationStatus={}", tenantId, NOT_REQUESTED);
            meters.counter("boss.admin.tenant.lifecycle", "action", "activate").increment();
            return new TenantActivationResult(tenantId, "active", NOT_REQUESTED);
        }

        try {
            CustomerOwnerInvitePort.OwnerInvite invite = invites.inviteInitialOwner(tenantId, adminEmail);
            transactions.executeWithoutResult(status -> support.audit(actor, "tenant.admin_invite_created", tenantId, null,
                    Map.of("inviteId", invite.inviteId().toString(), "invitationStatus", CREATED), ipAddress));
            log.info("Tenant activated tenantId={} invitationStatus={}", tenantId, CREATED);
            meters.counter("boss.admin.tenant.lifecycle", "action", "activate").increment();
            meters.counter("boss.admin.tenant.invite", "result", "created").increment();
            return new TenantActivationResult(tenantId, "active", CREATED);
        } catch (IdentityInviteUnavailableException exception) {
            transactions.executeWithoutResult(status -> support.audit(actor, "tenant.admin_invite_retry_required", tenantId,
                    null, Map.of("invitationStatus", RETRY_REQUIRED, "reasonCode", "DELIVERY_OR_GENERATION_FAILED"),
                    ipAddress));
            log.info("Tenant activated tenantId={} invitationStatus={}", tenantId, RETRY_REQUIRED);
            meters.counter("boss.admin.tenant.lifecycle", "action", "activate").increment();
            meters.counter("boss.admin.tenant.invite", "result", "unavailable").increment();
            return new TenantActivationResult(tenantId, "active", RETRY_REQUIRED);
        }
    }

    @Transactional
    public void suspend(UUID tenantId, String reason, AdminPrincipal actor, String ipAddress) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is required");
        }
        support.setTenantContext(tenantId);
        String oldStatus = support.lockTenantStatus(tenantId);
        if ("suspended".equals(oldStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant is already suspended");
        }
        if (!"active".equals(oldStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an active tenant can be suspended");
        }
        jdbc.update("UPDATE tenant_registry SET status = 'suspended', updated_at = now() WHERE id = ?", tenantId);
        support.audit(actor, "tenant.suspended", tenantId, Map.of("status", oldStatus),
                Map.of("status", "suspended", "reason", reason), ipAddress);
        log.info("Tenant suspended tenantId={}", tenantId);
        meters.counter("boss.admin.tenant.lifecycle", "action", "suspend").increment();
    }

    @Transactional
    public void reactivate(UUID tenantId, AdminPrincipal actor, String ipAddress) {
        support.setTenantContext(tenantId);
        String oldStatus = support.lockTenantStatus(tenantId);
        if (!"suspended".equals(oldStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a suspended tenant can be reactivated");
        }
        jdbc.update("UPDATE tenant_registry SET status = 'active', updated_at = now() WHERE id = ?", tenantId);
        support.audit(actor, "tenant.reactivated", tenantId,
                Map.of("status", oldStatus), Map.of("status", "active"), ipAddress);
        log.info("Tenant reactivated tenantId={}", tenantId);
        meters.counter("boss.admin.tenant.lifecycle", "action", "reactivate").increment();
    }

    public AdminHttpResult retryInvite(UUID tenantId, String email, String idempotencyKey,
                                       AdminPrincipal actor, String ipAddress) {
        support.validateKey(idempotencyKey);
        String hash = PlatformIdempotencyStore.requestHash("POST",
                PROVISION_PATH + "/" + tenantId + "/admin-invite", support.jsonBytes(Map.of("email", email)));
        PlatformIdempotencyStore.Claim claim = idempotency.claim(idempotencyKey, hash);
        if (claim instanceof PlatformIdempotencyStore.Replay replay) return support.replay(replay);
        UUID lockId = ((PlatformIdempotencyStore.Proceed) claim).rowId();
        try {
            requireTenant(tenantId);
            requireActiveTenant(tenantId);
            if (inviteCreated(tenantId)) {
                AdminHttpResult conflict = support.stored(lockId, HttpStatus.CONFLICT.value(),
                        Map.of("error", "INVITE_ALREADY_CREATED",
                                "message", "An initial administrator invitation was already created"));
                return conflict;
            }
            updateInitialAdminEmail(tenantId, email.trim(), actor, ipAddress);
            CustomerOwnerInvitePort.OwnerInvite invite = invites.inviteInitialOwner(tenantId, email);
            transactions.executeWithoutResult(status -> support.audit(actor, "tenant.admin_invite_created", tenantId, null,
                    Map.of("inviteId", invite.inviteId().toString(), "invitationStatus", CREATED), ipAddress));
            AdminInviteResult body = new AdminInviteResult(tenantId, invite.inviteId(), invite.oneTimeToken(),
                    CREATED, invite.expiresAt());
            log.info("Initial administrator invitation created tenantId={} inviteId={}", tenantId, invite.inviteId());
            meters.counter("boss.admin.tenant.invite", "result", "created").increment();
            return support.stored(lockId, HttpStatus.CREATED.value(), body);
        } catch (IdentityInviteUnavailableException exception) {
            idempotency.expire(lockId);
            transactions.executeWithoutResult(status -> support.audit(actor, "tenant.admin_invite_retry_required", tenantId,
                    null, Map.of("invitationStatus", RETRY_REQUIRED,
                            "reasonCode", "DELIVERY_OR_GENERATION_FAILED"), ipAddress));
            meters.counter("boss.admin.tenant.invite", "result", "unavailable").increment();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "IDENTITY_INVITE_UNAVAILABLE: " + exception.getMessage());
        } catch (RuntimeException exception) {
            idempotency.expire(lockId);
            throw exception;
        }
    }

    private CreatedTenant createTenant(TenantProvisionRequest request, String groupSlug, String tenantSlug,
                                       AdminPrincipal actor, String ipAddress) {
        UUID groupId = Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO customer_groups(name, slug) VALUES (?, ?) RETURNING id",
                UUID.class, request.customerGroupName(), groupSlug));
        UUID tenantId = UUID.randomUUID();
        support.setTenantContext(tenantId);
        jdbc.update("INSERT INTO tenant_registry(id, customer_group_id, name, slug, status, plan_tier, initial_admin_email) " +
                        "VALUES (?, ?, ?, ?, 'provisioning', ?, ?)",
                tenantId, groupId, request.tenantName(), tenantSlug, request.planTier(), normalizedEmail(request.adminEmail()));

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
        support.audit(actor, "tenant.provisioned", tenantId, null, Map.of(
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

    private boolean inviteCreated(UUID tenantId) {
        List<String> statuses = jdbc.query(
                "SELECT new_data->>'invitationStatus' FROM platform_audit_events " +
                        "WHERE entity_id = ? AND action = 'tenant.admin_invite_created' ORDER BY created_at DESC LIMIT 1",
                (rs, row) -> rs.getString(1), tenantId);
        return !statuses.isEmpty() && CREATED.equals(statuses.getFirst());
    }

    private void requireActiveTenant(UUID tenantId) {
        List<String> statuses = jdbc.query(
                "SELECT status FROM tenant_registry WHERE id = ?",
                (rs, row) -> rs.getString("status"), tenantId);
        if (statuses.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant was not found");
        }
        if (!"active".equals(statuses.getFirst())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Administrator invitations require an active tenant");
        }
    }

    private void updateInitialAdminEmail(UUID tenantId, String email, AdminPrincipal actor, String ipAddress) {
        transactions.executeWithoutResult(status -> {
            support.setTenantContext(tenantId);
            List<String> currentEmails = jdbc.query(
                    "SELECT initial_admin_email FROM tenant_registry WHERE id = ? FOR UPDATE",
                    (rs, row) -> rs.getString("initial_admin_email"), tenantId);
            if (currentEmails.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant was not found");
            }
            String oldEmail = currentEmails.getFirst();
            if (!Objects.equals(oldEmail, email)) {
                jdbc.update("UPDATE tenant_registry SET initial_admin_email = ?, updated_at = now() WHERE id = ?",
                        email, tenantId);
                support.audit(actor, "tenant.admin_contact_updated", tenantId,
                        Map.of("adminEmailChanged", oldEmail != null),
                        Map.of("adminEmailChanged", true), ipAddress);
            }
        });
    }

    private String normalizedEmail(String email) {
        return support.hasEmail(email) ? email.trim() : null;
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

    private String provisionHash(TenantProvisionRequest request) {
        return PlatformIdempotencyStore.requestHash("POST", PROVISION_PATH, support.jsonBytes(request));
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
