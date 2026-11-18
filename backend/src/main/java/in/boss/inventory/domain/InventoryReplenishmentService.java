package in.boss.inventory.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.inventory.api.InventoryApiDtos.InventoryPage;
import in.boss.inventory.api.InventoryApiDtos.ReorderPolicyRequest;
import in.boss.inventory.api.InventoryApiDtos.ReorderPolicyResponse;
import in.boss.inventory.api.InventoryApiDtos.ReplenishmentRequestResponse;
import in.boss.inventory.events.InventoryMetrics;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;

@Service
@Transactional
public class InventoryReplenishmentService {

    private static final int MAX_PAGE_SIZE = 200;

    private final JdbcTemplate jdbc;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final InventoryMetrics metrics;
    private final InventoryFeatureFlags featureFlags;

    public InventoryReplenishmentService(JdbcTemplate jdbc, AuditService auditService,
                                         ObjectMapper objectMapper, InventoryMetrics metrics,
                                         InventoryFeatureFlags featureFlags) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.featureFlags = featureFlags;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise replenishment audit payload", e);
        }
    }

    public ReorderPolicyResponse upsertPolicy(ReorderPolicyRequest request) {
        BossContext context = requireContext();
        featureFlags.require(context.tenantId(), InventoryFeatureFlags.REPLENISHMENT);
        UUID tenantId = context.tenantId();
        validatePolicyRequest(context, request);
        ReorderPolicyResponse before = findPolicyByItemStore(tenantId, request.itemId(), request.storeId());

        UUID policyId = jdbc.queryForObject("""
                INSERT INTO reorder_policies
                  (tenant_id, item_id, store_id, reorder_point, reorder_qty, max_stock,
                   lead_time_days, preferred_supplier_id, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, item_id, store_id)
                DO UPDATE SET reorder_point = EXCLUDED.reorder_point,
                              reorder_qty = EXCLUDED.reorder_qty,
                              max_stock = EXCLUDED.max_stock,
                              lead_time_days = EXCLUDED.lead_time_days,
                              preferred_supplier_id = EXCLUDED.preferred_supplier_id,
                              is_active = EXCLUDED.is_active,
                              updated_at = NOW()
                RETURNING id
                """, UUID.class, tenantId, request.itemId(), request.storeId(),
                request.reorderPoint(), request.reorderQty(), request.maxStock(),
                request.leadTimeDays(), request.preferredSupplierId(),
                request.active() == null || request.active());
        ReorderPolicyResponse policy = findPolicy(tenantId, policyId);
        auditService.record("REORDER_POLICY", policyId, before == null ? "CREATE" : "UPDATE",
                before == null ? null : json(before), json(policy));
        checkAfterStockChange(tenantId, request.itemId(), request.storeId());
        return policy;
    }

    private void validatePolicyRequest(BossContext context, ReorderPolicyRequest request) {
        validateThresholds(request);
        requireItem(context.tenantId(), request.itemId());
        requirePolicyStore(context, request.storeId());
        validateOptionalPolicyFields(context.tenantId(), request);
    }

    private static void validateThresholds(ReorderPolicyRequest request) {
        if (request.reorderPoint() == null || request.reorderQty() == null
                || request.reorderPoint().signum() < 0 || request.reorderQty().signum() <= 0) {
            throw new InventoryValidationException("A non-negative reorder point and positive reorder quantity are required");
        }
    }

    private void requirePolicyStore(BossContext context, UUID storeId) {
        UUID siteId = storeSiteId(context.tenantId(), storeId);
        if (!context.canAccessSite(siteId)) {
            throw new InventoryValidationException("Store is outside the caller's site");
        }
    }

    private void validateOptionalPolicyFields(UUID tenantId, ReorderPolicyRequest request) {
        if (request.leadTimeDays() != null && request.leadTimeDays() < 0) {
            throw new InventoryValidationException("Lead time cannot be negative");
        }
        if (request.maxStock() != null
                && request.maxStock().compareTo(request.reorderPoint()) < 0) {
            throw new InventoryValidationException("Maximum stock cannot be below the reorder point");
        }
        if (request.preferredSupplierId() != null && !supplierExists(tenantId, request.preferredSupplierId())) {
            throw new InventoryValidationException("Preferred supplier not found for this tenant");
        }
    }

    @Transactional(readOnly = true)
    public InventoryPage<ReorderPolicyResponse> listPolicies(UUID storeId, int page, int size) {
        BossContext context = requireContext();
        validatePage(page, size);
        StringBuilder where = new StringBuilder(" WHERE rp.tenant_id = ?");
        List<Object> args = new ArrayList<>(List.of(context.tenantId()));
        if (storeId != null) {
            UUID siteId = storeSiteId(context.tenantId(), storeId);
            if (!context.canAccessSite(siteId)) {
                return new InventoryPage<>(List.of(), page, 0);
            }
            where.append(" AND rp.store_id = ?");
            args.add(storeId);
        }
        if (context.isSiteScoped()) {
            where.append(" AND s.site_id = ?");
            args.add(context.siteId());
        }
        String from = """
                FROM reorder_policies rp
                JOIN stores s ON s.id = rp.store_id AND s.tenant_id = rp.tenant_id
                """ + where;
        Long count = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, args.toArray());
        args.add(size);
        args.add(page * size);
        List<ReorderPolicyResponse> content = jdbc.query("""
                SELECT rp.id, rp.item_id, rp.store_id, rp.reorder_point, rp.reorder_qty,
                       rp.max_stock, rp.lead_time_days, rp.preferred_supplier_id, rp.is_active
                """ + from + " ORDER BY rp.item_id, rp.store_id LIMIT ? OFFSET ?",
                this::mapPolicy, args.toArray());
        return new InventoryPage<>(content, page, count == null ? 0 : count);
    }

    @Transactional(readOnly = true)
    public InventoryPage<ReplenishmentRequestResponse> listRequests(
            String status, UUID storeId, UUID itemId, int page, int size) {
        BossContext context = requireContext();
        validatePage(page, size);
        StringBuilder where = new StringBuilder(" WHERE rr.tenant_id = ?");
        List<Object> args = new ArrayList<>(List.of(context.tenantId()));
        if (status != null && !status.isBlank()) {
            String normalizedStatus = status.trim().toLowerCase(Locale.ROOT);
            if (!List.of("pending", "pr_created", "fulfilled", "cancelled").contains(normalizedStatus)) {
                throw new InventoryValidationException("Invalid replenishment request status");
            }
            where.append(" AND rr.status = ?");
            args.add(normalizedStatus);
        }
        if (storeId != null) {
            UUID siteId = storeSiteId(context.tenantId(), storeId);
            if (!context.canAccessSite(siteId)) {
                return new InventoryPage<>(List.of(), page, 0);
            }
            where.append(" AND rr.store_id = ?");
            args.add(storeId);
        }
        if (itemId != null) {
            where.append(" AND rr.item_id = ?");
            args.add(itemId);
        }
        if (context.isSiteScoped()) {
            where.append(" AND s.site_id = ?");
            args.add(context.siteId());
        }
        String from = """
                FROM replenishment_requests rr
                JOIN items i ON i.id = rr.item_id AND i.tenant_id = rr.tenant_id
                JOIN stores s ON s.id = rr.store_id AND s.tenant_id = rr.tenant_id
                """ + where;
        Long count = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, args.toArray());
        args.add(size);
        args.add(page * size);
        List<ReplenishmentRequestResponse> content = jdbc.query("""
                SELECT rr.id, rr.item_id, i.code AS item_code, i.name AS item_name,
                       rr.store_id, s.name AS store_name, rr.policy_id, rr.requested_qty,
                       rr.status, rr.pr_id, rr.created_at
                """ + from + " ORDER BY rr.created_at DESC, rr.id LIMIT ? OFFSET ?",
                this::mapRequest, args.toArray());
        return new InventoryPage<>(content, page, count == null ? 0 : count);
    }

    public ReplenishmentRequestResponse cancelRequest(UUID requestId) {
        BossContext context = requireContext();
        List<ReplenishmentRequestResponse> rows = jdbc.query("""
                SELECT rr.id, rr.item_id, i.code AS item_code, i.name AS item_name,
                       rr.store_id, s.name AS store_name, rr.policy_id, rr.requested_qty,
                       rr.status, rr.pr_id, rr.created_at
                  FROM replenishment_requests rr
                  JOIN items i ON i.id = rr.item_id AND i.tenant_id = rr.tenant_id
                  JOIN stores s ON s.id = rr.store_id AND s.tenant_id = rr.tenant_id
                 WHERE rr.tenant_id = ? AND rr.id = ?
                 FOR UPDATE OF rr
                """, this::mapRequest, context.tenantId(), requestId);
        if (rows.isEmpty()) {
            throw new InventoryValidationException("Replenishment request not found");
        }
        ReplenishmentRequestResponse request = rows.get(0);
        if (!context.canAccessSite(storeSiteId(context.tenantId(), request.storeId()))) {
            throw new InventoryValidationException("Replenishment request not found");
        }
        if (!"pending".equals(request.status())) {
            throw new ReservationConflictException(
                    "Only pending replenishment requests can be cancelled");
        }
        jdbc.update("""
                UPDATE replenishment_requests SET status = 'cancelled'
                 WHERE tenant_id = ? AND id = ? AND status = 'pending'
                """, context.tenantId(), requestId);
        auditService.record("REPLENISHMENT_REQUEST", requestId, "CANCEL",
                json(Map.of("status", request.status())), json(Map.of("status", "cancelled")));
        metrics.replenishmentRequest("cancelled");
        return new ReplenishmentRequestResponse(request.id(), request.itemId(), request.itemCode(),
                request.itemName(), request.storeId(), request.storeName(), request.policyId(),
                request.requestedQty(), "cancelled", request.prId(), request.createdAt());
    }

    public void checkAfterStockChange(UUID tenantId, UUID itemId, UUID storeId) {
        if (!featureFlags.enabled(tenantId, InventoryFeatureFlags.REPLENISHMENT)) {
            return;
        }
        List<PolicyThreshold> policies = jdbc.query("""
                SELECT id, reorder_point, reorder_qty
                  FROM reorder_policies
                 WHERE tenant_id = ? AND item_id = ? AND store_id = ? AND is_active
                 FOR UPDATE
                """, (rs, rowNum) -> new PolicyThreshold(rs.getObject("id", UUID.class),
                rs.getBigDecimal("reorder_point"), rs.getBigDecimal("reorder_qty")),
                tenantId, itemId, storeId);
        for (PolicyThreshold policy : policies) {
            BigDecimal available = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(quantity - reserved_qty), 0)
                      FROM material_lots
                     WHERE tenant_id = ? AND item_id = ? AND store_id = ?
                       AND status IN ('available', 'reserved')
                       AND (expiry_date IS NULL OR expiry_date >= CURRENT_DATE)
                    """, BigDecimal.class, tenantId, itemId, storeId);
            if (available == null || available.compareTo(policy.reorderPoint()) >= 0) {
                continue;
            }
            Integer existing = jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM replenishment_requests
                     WHERE tenant_id = ? AND policy_id = ?
                       AND status IN ('pending', 'pr_created')
                    """, Integer.class, tenantId, policy.id());
            if (existing != null && existing > 0) {
                continue;
            }
            UUID requestId = jdbc.queryForObject("""
                    INSERT INTO replenishment_requests
                      (tenant_id, policy_id, item_id, store_id, requested_qty, status)
                    VALUES (?, ?, ?, ?, ?, 'pending')
                    RETURNING id
                    """, UUID.class, tenantId, policy.id(), itemId, storeId, policy.reorderQty());
            auditService.record("REPLENISHMENT_REQUEST", requestId, "CREATE", null,
                    json(Map.of("policyId", policy.id(), "itemId", itemId,
                            "storeId", storeId, "requestedQty", policy.reorderQty())));
            metrics.replenishmentRequest("created");
        }
    }

    private ReorderPolicyResponse findPolicy(UUID tenantId, UUID id) {
        return jdbc.queryForObject("""
                SELECT id, item_id, store_id, reorder_point, reorder_qty, max_stock,
                       lead_time_days, preferred_supplier_id, is_active
                  FROM reorder_policies
                 WHERE tenant_id = ? AND id = ?
                """, this::mapPolicy, tenantId, id);
    }

    private ReorderPolicyResponse findPolicyByItemStore(UUID tenantId, UUID itemId, UUID storeId) {
        List<ReorderPolicyResponse> rows = jdbc.query("""
                SELECT id, item_id, store_id, reorder_point, reorder_qty, max_stock,
                       lead_time_days, preferred_supplier_id, is_active
                  FROM reorder_policies
                 WHERE tenant_id = ? AND item_id = ? AND store_id = ?
                 FOR UPDATE
                """, this::mapPolicy, tenantId, itemId, storeId);
        return rows.stream().findFirst().orElse(null);
    }

    private ReorderPolicyResponse mapPolicy(ResultSet rs, int rowNum) throws SQLException {
        return new ReorderPolicyResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getBigDecimal("reorder_point"),
                rs.getBigDecimal("reorder_qty"),
                rs.getBigDecimal("max_stock"),
                (Integer) rs.getObject("lead_time_days"),
                rs.getObject("preferred_supplier_id", UUID.class),
                rs.getBoolean("is_active"));
    }

    private ReplenishmentRequestResponse mapRequest(ResultSet rs, int rowNum) throws SQLException {
        return new ReplenishmentRequestResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getString("item_code"),
                rs.getString("item_name"),
                rs.getObject("store_id", UUID.class),
                rs.getString("store_name"),
                rs.getObject("policy_id", UUID.class),
                rs.getBigDecimal("requested_qty"),
                rs.getString("status"),
                rs.getObject("pr_id", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }

    private UUID storeSiteId(UUID tenantId, UUID storeId) {
        List<UUID> sites = jdbc.query("""
                SELECT site_id FROM stores WHERE tenant_id = ? AND id = ?
                """, (rs, rowNum) -> rs.getObject("site_id", UUID.class), tenantId, storeId);
        if (sites.isEmpty()) {
            throw new InventoryValidationException("Store not found for this tenant");
        }
        return sites.get(0);
    }

    private void requireItem(UUID tenantId, UUID itemId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM items WHERE tenant_id = ? AND id = ?",
                Integer.class, tenantId, itemId);
        if (count == null || count == 0) {
            throw new InventoryValidationException("Item not found for this tenant");
        }
    }

    private boolean supplierExists(UUID tenantId, UUID supplierId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM suppliers WHERE tenant_id = ? AND id = ?",
                Integer.class, tenantId, supplierId);
        return count != null && count > 0;
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InventoryValidationException("Page must be non-negative and size must be 1..200");
        }
    }

    private static BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new IllegalStateException("Tenant context is required");
        }
        return context;
    }

    private record PolicyThreshold(UUID id, BigDecimal reorderPoint, BigDecimal reorderQty) {}
}
