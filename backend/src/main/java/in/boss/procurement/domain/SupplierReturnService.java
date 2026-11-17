package in.boss.procurement.domain;

import in.boss.inventory.api.InventoryApiDtos.AddMovementRequest;
import in.boss.inventory.domain.InventoryService;
import in.boss.procurement.api.ProcurementApiDtos.CreateSupplierReturnLineRequest;
import in.boss.procurement.api.ProcurementApiDtos.CreateSupplierReturnRequest;
import in.boss.procurement.api.ProcurementApiDtos.SupplierReturnResponse;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@PreAuthorize("hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER','PURCHASE_MANAGER')")
public class SupplierReturnService {

    private final JdbcTemplate jdbc;
    private final InventoryService inventoryService;
    private final AuditService auditService;

    public SupplierReturnService(JdbcTemplate jdbc, InventoryService inventoryService, AuditService auditService) {
        this.jdbc = jdbc;
        this.inventoryService = inventoryService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public SupplierReturnResponse get(UUID returnId) {
        BossContext context = requireContext();
        List<SupplierReturnResponse> rows = jdbc.query("""
                SELECT r.id, r.return_number, r.grn_id, r.spo_id, r.supplier_id, r.status, r.reason
                FROM supplier_returns r
                WHERE r.tenant_id = ? AND r.id = ?
                """, (rs, rowNum) -> new SupplierReturnResponse(
                rs.getObject("id", UUID.class),
                rs.getString("return_number"),
                rs.getObject("grn_id", UUID.class),
                rs.getObject("spo_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("status"),
                rs.getString("reason")), context.tenantId(), returnId);
        if (rows.isEmpty()) {
            throw notFound("Supplier return not found");
        }
        return rows.get(0);
    }

    @Transactional
    public SupplierReturnResponse create(CreateSupplierReturnRequest request) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        GrnRow grn = jdbc.query("""
                SELECT id, supplier_id, spo_id FROM grns
                WHERE tenant_id = ? AND id = ? FOR UPDATE
                """, rs -> rs.next() ? new GrnRow(
                rs.getObject("id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getObject("spo_id", UUID.class)) : null, tenantId, request.grnId());
        if (grn == null) {
            throw notFound("Goods receipt not found");
        }
        UUID returnId = UUID.randomUUID();
        String number = "SR-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + returnId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO supplier_returns
                  (id, tenant_id, return_number, grn_id, spo_id, supplier_id, status, reason, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'posted', ?, ?)
                """, returnId, tenantId, number, grn.id(), grn.spoId(), grn.supplierId(),
                request.reason().trim(), context.userId());
        for (CreateSupplierReturnLineRequest line : request.lines()) {
            GrnLineRow source = jdbc.query("""
                    SELECT id, accepted_qty, item_id, uom_id FROM grn_lines
                    WHERE tenant_id = ? AND grn_id = ? AND id = ? FOR UPDATE
                    """, rs -> rs.next() ? new GrnLineRow(
                    rs.getObject("id", UUID.class), rs.getBigDecimal("accepted_qty"),
                    rs.getObject("item_id", UUID.class), rs.getObject("uom_id", UUID.class)) : null,
                    tenantId, grn.id(), line.grnLineId());
            if (source == null) {
                throw badRequest("Return line is not on this goods receipt");
            }
            BigDecimal returned = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(l.quantity), 0)
                    FROM supplier_return_lines l
                    JOIN supplier_returns r ON r.id = l.return_id AND r.tenant_id = l.tenant_id
                    WHERE l.tenant_id = ? AND l.grn_line_id = ? AND r.status <> 'cancelled'
                    """, BigDecimal.class, tenantId, source.id());
            if (returned.add(line.quantity()).compareTo(source.acceptedQty()) > 0) {
                throw conflict("Return quantity exceeds the accepted quantity still available to return");
            }
            UUID lineId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO supplier_return_lines
                      (id, tenant_id, return_id, grn_line_id, lot_id, item_id, quantity, uom_id, reason)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, lineId, tenantId, returnId, source.id(), line.lotId(), source.itemId(),
                    line.quantity(), source.uomId(), request.reason().trim());
            inventoryService.addMovement(line.lotId(), new AddMovementRequest(
                    "issue", line.quantity(), "SUPPLIER_RETURN", lineId, request.reason().trim()));
        }
        SupplierReturnResponse response = get(returnId);
        auditService.record("supplier_returns", returnId, "POSTED", null, "{\"status\":\"posted\"}");
        return response;
    }

    private BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null || context.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant user context is required");
        }
        return context;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record GrnRow(UUID id, UUID supplierId, UUID spoId) {}

    private record GrnLineRow(UUID id, BigDecimal acceptedQty, UUID itemId, UUID uomId) {}
}
