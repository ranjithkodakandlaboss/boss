package in.boss.procurement.domain;

import in.boss.inventory.api.InventoryApiDtos.AddMovementRequest;
import in.boss.inventory.api.InventoryApiDtos.CreateLotRequest;
import in.boss.inventory.domain.InventoryService;
import in.boss.procurement.api.ProcurementApiDtos.CompleteInspectionRequest;
import in.boss.procurement.api.ProcurementApiDtos.InspectionResponse;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import in.boss.shared.security.BossPermissions;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Incoming inspection for a goods-receipt lot.
 *
 * Received stock stays in quarantine. A full accept releases that same lot.
 * A full reject leaves it quarantined and records an NCR. A partial accept,
 * which requires an owner or operations manager and a note, releases only the
 * accepted quantity and records an NCR plus an approved concession for the rest.
 */
@Service
@PreAuthorize(BossPermissions.QUALITY_WRITE)
public class IncomingInspectionService {

    private final JdbcTemplate jdbc;
    private final InventoryService inventoryService;
    private final PurchaseRequisitionService prService;
    private final AuditService auditService;

    public IncomingInspectionService(JdbcTemplate jdbc,
                                     InventoryService inventoryService,
                                     PurchaseRequisitionService prService,
                                     AuditService auditService) {
        this.jdbc = jdbc;
        this.inventoryService = inventoryService;
        this.prService = prService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<InspectionResponse> list(UUID grnId) {
        BossContext context = requireContext();
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM grns WHERE tenant_id = ? AND id = ?",
                Integer.class, context.tenantId(), grnId);
        if (found == null || found == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Goods receipt not found");
        }
        return jdbc.query("""
                SELECT id, inspection_number, reference_id, lot_id, item_id,
                       quantity_inspected, quantity_accepted, quantity_rejected, result, status, notes
                FROM quality_inspections
                WHERE tenant_id = ? AND reference_type = 'grn' AND reference_id = ?
                ORDER BY created_at
                """, (rs, rowNum) -> mapInspection(rs), context.tenantId(), grnId);
    }

    @Transactional
    public InspectionResponse start(UUID grnId, UUID lotId) {
        BossContext context = requireContext();
        LotRow lot = lockReceiptLot(context.tenantId(), grnId, lotId);
        List<InspectionResponse> existing = findForLot(context.tenantId(), lot.id());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        UUID id = UUID.randomUUID();
        String number = "INSP-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO quality_inspections
                  (id, tenant_id, inspection_number, inspection_type, reference_type, reference_id,
                   item_id, lot_id, quantity_inspected, status, inspector_id, inspection_date)
                VALUES (?, ?, ?, 'incoming', 'grn', ?, ?, ?, ?, 'in_progress', ?, CURRENT_DATE)
                """, id, context.tenantId(), number, grnId, lot.itemId(), lot.id(),
                lot.quantity(), context.userId());
        auditService.record("quality_inspections", id, "STARTED", null, "{\"grnId\":\"" + grnId + "\"}");
        return get(id);
    }

    @Transactional(readOnly = true)
    public InspectionResponse get(UUID inspectionId) {
        BossContext context = requireContext();
        List<InspectionResponse> rows = jdbc.query("""
                SELECT id, inspection_number, reference_id, lot_id, item_id,
                       quantity_inspected, quantity_accepted, quantity_rejected, result, status, notes
                FROM quality_inspections
                WHERE tenant_id = ? AND id = ? AND inspection_type = 'incoming'
                """, (rs, rowNum) -> new InspectionResponse(
                rs.getObject("id", UUID.class),
                rs.getString("inspection_number"),
                rs.getObject("reference_id", UUID.class),
                rs.getObject("lot_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("quantity_inspected"),
                rs.getBigDecimal("quantity_accepted"),
                rs.getBigDecimal("quantity_rejected"),
                rs.getString("result"),
                rs.getString("status"),
                rs.getString("notes")), context.tenantId(), inspectionId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Incoming inspection not found");
        }
        return rows.get(0);
    }

    @Transactional
    public InspectionResponse complete(UUID inspectionId, CompleteInspectionRequest request) {
        BossContext context = requireContext();
        InspectionResponse current = lockInspection(context.tenantId(), inspectionId);
        if ("completed".equals(current.status())) {
            return current;
        }
        if (request.hold()) {
            jdbc.update("""
                    UPDATE quality_inspections
                    SET notes = ?, updated_at = NOW()
                    WHERE tenant_id = ? AND id = ?
                    """, blank(request.notes()), context.tenantId(), inspectionId);
            auditService.record("quality_inspections", inspectionId, "HELD", null, null);
            return get(inspectionId);
        }
        BigDecimal accepted = request.acceptedQty();
        BigDecimal rejected = request.rejectedQty();
        if (accepted.add(rejected).compareTo(current.quantityInspected()) != 0) {
            throw badRequest("Accepted and rejected quantities must account for the whole lot, or the lot must stay on hold");
        }
        boolean partial = accepted.signum() > 0 && rejected.signum() > 0;
        if (partial && !context.hasAnyRole("OWNER", "OPS_MANAGER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A partial accept requires the owner or operations manager");
        }
        if (partial && (request.notes() == null || request.notes().isBlank())) {
            throw badRequest("A partial accept requires the concession note");
        }
        LotRow lot = lockLot(context.tenantId(), current.lotId());
        if (!"quarantine".equals(lot.status())) {
            throw conflict("This lot is no longer waiting for inspection");
        }
        if (rejected.signum() == 0) {
            inventoryService.transitionLotStatus(lot.id(), "available");
        } else if (accepted.signum() > 0) {
            inventoryService.addMovement(lot.id(), new AddMovementRequest(
                    "transfer_out", accepted, "INSPECTION", inspectionId, "Accepted quantity released"));
            inventoryService.createLot(new CreateLotRequest(
                    lot.itemId(), lot.storeId(), accepted, lot.unitCost(), lot.batchRef(),
                    lot.expiryDate(), "available", null));
        }
        String result = rejected.signum() == 0 ? "pass" : accepted.signum() == 0 ? "fail" : "conditional";
        jdbc.update("""
                UPDATE quality_inspections
                SET quantity_accepted = ?, quantity_rejected = ?, result = ?, status = 'completed',
                    notes = ?, inspector_id = ?, inspection_date = CURRENT_DATE, updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """, accepted, rejected, result, blank(request.notes()), context.userId(),
                context.tenantId(), inspectionId);
        if (rejected.signum() > 0) {
            recordNonConformance(context, current, rejected, request.notes(), partial);
        }
        jdbc.update("""
                UPDATE grn_lines
                SET accepted_qty = accepted_qty + ?, rejected_qty = rejected_qty + ?
                WHERE tenant_id = ? AND id = ?
                """, accepted, rejected, context.tenantId(), lot.grnLineId());
        closeReceiptIfDecided(context.tenantId(), current.grnId());
        auditService.record("quality_inspections", inspectionId, "COMPLETED", null,
                "{\"result\":\"" + result + "\"}");
        return get(inspectionId);
    }

    private void recordNonConformance(BossContext context, InspectionResponse inspection,
                                      BigDecimal rejected, String notes, boolean partial) {
        UUID ncrId = UUID.randomUUID();
        String ncrNumber = "NCR-" + ncrId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO ncrs
                  (id, tenant_id, ncr_number, inspection_id, item_id, lot_id, quantity,
                   defect_type, defect_description, status, raised_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'non_conforming', ?, 'open', ?)
                """, ncrId, context.tenantId(), ncrNumber, inspection.id(), inspection.itemId(),
                inspection.lotId(), rejected, blank(notes), context.userId());
        auditService.record("ncrs", ncrId, "CREATE", null, null);
        if (partial) {
            UUID concessionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO concessions
                      (id, tenant_id, ncr_id, concession_type, approved_by, approval_notes, status)
                    VALUES (?, ?, ?, 'use_as_is', ?, ?, 'approved')
                    """, concessionId, context.tenantId(), ncrId, context.userId(), notes.trim());
            auditService.record("concessions", concessionId, "APPROVED", null, null);
        }
    }

    private void closeReceiptIfDecided(UUID tenantId, UUID grnId) {
        Integer open = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM stock_movements m
                JOIN material_lots l ON l.id = m.lot_id AND l.tenant_id = m.tenant_id
                WHERE m.tenant_id = ? AND m.reference_type = 'GRN' AND m.movement_type = 'receipt'
                  AND m.reference_id IN (SELECT id FROM grn_lines WHERE tenant_id = ? AND grn_id = ?)
                  AND NOT EXISTS (
                    SELECT 1 FROM quality_inspections q
                    WHERE q.tenant_id = m.tenant_id AND q.lot_id = l.id AND q.status = 'completed')
                """, Integer.class, tenantId, tenantId, grnId);
        if (open != null && open > 0) {
            return;
        }
        BigDecimal accepted = jdbc.queryForObject("""
                SELECT COALESCE(SUM(accepted_qty), 0) FROM grn_lines WHERE tenant_id = ? AND grn_id = ?
                """, BigDecimal.class, tenantId, grnId);
        BigDecimal rejected = jdbc.queryForObject("""
                SELECT COALESCE(SUM(rejected_qty), 0) FROM grn_lines WHERE tenant_id = ? AND grn_id = ?
                """, BigDecimal.class, tenantId, grnId);
        String outcome = rejected.signum() == 0 ? "accepted"
                : accepted.signum() == 0 ? "rejected" : "partially_accepted";
        jdbc.update("UPDATE grns SET status = 'closed', updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                tenantId, grnId);
        UUID spoId = jdbc.queryForObject("SELECT spo_id FROM grns WHERE tenant_id = ? AND id = ?",
                UUID.class, tenantId, grnId);
        Boolean complete = jdbc.queryForObject("""
                SELECT COUNT(*) = 0 FROM spo_lines
                WHERE tenant_id = ? AND spo_id = ? AND received_qty < quantity
                """, Boolean.class, tenantId, spoId);
        String spoStatus = Boolean.TRUE.equals(complete) ? "fully_received" : "partially_received";
        jdbc.update("""
                UPDATE spos SET status = ?, updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                  AND status IN ('issued', 'acknowledged', 'partially_received')
                  AND EXISTS (
                    SELECT 1 FROM spo_lines WHERE tenant_id = ? AND spo_id = ? AND received_qty > 0)
                """, spoStatus, tenantId, spoId, tenantId, spoId);
        Integer recorded = jdbc.queryForObject("""
                SELECT COUNT(*) FROM supplier_performance_records
                WHERE tenant_id = ? AND grn_id = ?
                """, Integer.class, tenantId, grnId);
        if (recorded == null || recorded == 0) {
            jdbc.update("""
                    INSERT INTO supplier_performance_records
                      (tenant_id, supplier_id, spo_id, grn_id, promised_date, actual_date,
                       quantity_ordered, quantity_received, quantity_rejected, quality_pass, notes)
                    SELECT g.tenant_id, g.supplier_id, g.spo_id, g.id, s.expected_delivery, g.received_date,
                           (SELECT COALESCE(SUM(quantity), 0) FROM spo_lines WHERE tenant_id = g.tenant_id AND spo_id = g.spo_id),
                           (SELECT COALESCE(SUM(received_qty), 0) FROM grn_lines WHERE tenant_id = g.tenant_id AND grn_id = g.id),
                           ?, ?, ?
                    FROM grns g
                    JOIN spos s ON s.id = g.spo_id AND s.tenant_id = g.tenant_id
                    WHERE g.tenant_id = ? AND g.id = ?
                    """, rejected, rejected.signum() == 0, outcome, tenantId, grnId);
        }
        UUID prId = jdbc.query("""
                SELECT pr_id FROM spos WHERE tenant_id = ? AND id = ?
                """, rs -> rs.next() ? rs.getObject("pr_id", UUID.class) : null, tenantId, spoId);
        if (prId != null) {
            prService.recalculateCoverage(prId);
        }
        auditService.record("grns", grnId, "CLOSED", null, "{\"outcome\":\"" + outcome + "\"}");
    }

    private LotRow lockReceiptLot(UUID tenantId, UUID grnId, UUID lotId) {
        if (lotId == null) {
            throw badRequest("The goods-receipt lot is required");
        }
        List<LotRow> rows = jdbc.query("""
                SELECT l.id, l.item_id, l.store_id, l.quantity, l.status, l.unit_cost,
                       l.batch_number, l.expiry_date, m.reference_id
                FROM material_lots l
                JOIN stock_movements m ON m.lot_id = l.id AND m.tenant_id = l.tenant_id
                JOIN grn_lines gl ON gl.id = m.reference_id AND gl.tenant_id = l.tenant_id
                WHERE l.tenant_id = ? AND l.id = ? AND gl.grn_id = ?
                  AND m.reference_type = 'GRN' AND m.movement_type = 'receipt'
                FOR UPDATE OF l
                """, (rs, rowNum) -> new LotRow(
                rs.getObject("id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getBigDecimal("quantity"),
                rs.getString("status"),
                rs.getBigDecimal("unit_cost"),
                rs.getString("batch_number"),
                rs.getObject("expiry_date", LocalDate.class),
                rs.getObject("reference_id", UUID.class)), tenantId, lotId, grnId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Goods-receipt lot not found");
        }
        return rows.get(0);
    }

    private LotRow lockLot(UUID tenantId, UUID lotId) {
        List<LotRow> rows = jdbc.query("""
                SELECT l.id, l.item_id, l.store_id, l.quantity, l.status, l.unit_cost,
                       l.batch_number, l.expiry_date, m.reference_id
                FROM material_lots l
                JOIN stock_movements m ON m.lot_id = l.id AND m.tenant_id = l.tenant_id
                WHERE l.tenant_id = ? AND l.id = ?
                  AND m.reference_type = 'GRN' AND m.movement_type = 'receipt'
                FOR UPDATE OF l
                """, (rs, rowNum) -> new LotRow(
                rs.getObject("id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getBigDecimal("quantity"),
                rs.getString("status"),
                rs.getBigDecimal("unit_cost"),
                rs.getString("batch_number"),
                rs.getObject("expiry_date", LocalDate.class),
                rs.getObject("reference_id", UUID.class)), tenantId, lotId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Inspection lot not found");
        }
        return rows.get(0);
    }

    private List<InspectionResponse> findForLot(UUID tenantId, UUID lotId) {
        return jdbc.query("""
                SELECT id, inspection_number, reference_id, lot_id, item_id,
                       quantity_inspected, quantity_accepted, quantity_rejected, result, status, notes
                FROM quality_inspections
                WHERE tenant_id = ? AND lot_id = ? AND inspection_type = 'incoming'
                  AND status IN ('in_progress', 'completed')
                ORDER BY created_at
                LIMIT 1
                """, (rs, rowNum) -> new InspectionResponse(
                rs.getObject("id", UUID.class),
                rs.getString("inspection_number"),
                rs.getObject("reference_id", UUID.class),
                rs.getObject("lot_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("quantity_inspected"),
                rs.getBigDecimal("quantity_accepted"),
                rs.getBigDecimal("quantity_rejected"),
                rs.getString("result"),
                rs.getString("status"),
                rs.getString("notes")), tenantId, lotId);
    }

    private InspectionResponse lockInspection(UUID tenantId, UUID inspectionId) {
        List<InspectionResponse> rows = jdbc.query("""
                SELECT id, inspection_number, reference_id, lot_id, item_id,
                       quantity_inspected, quantity_accepted, quantity_rejected, result, status, notes
                FROM quality_inspections
                WHERE tenant_id = ? AND id = ? AND inspection_type = 'incoming'
                FOR UPDATE
                """, (rs, rowNum) -> new InspectionResponse(
                rs.getObject("id", UUID.class),
                rs.getString("inspection_number"),
                rs.getObject("reference_id", UUID.class),
                rs.getObject("lot_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("quantity_inspected"),
                rs.getBigDecimal("quantity_accepted"),
                rs.getBigDecimal("quantity_rejected"),
                rs.getString("result"),
                rs.getString("status"),
                rs.getString("notes")), tenantId, inspectionId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Incoming inspection not found");
        }
        return rows.get(0);
    }

    private BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null || context.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant user context is required");
        }
        return context;
    }

    private static InspectionResponse mapInspection(ResultSet rs) throws SQLException {
        return new InspectionResponse(
                rs.getObject("id", UUID.class),
                rs.getString("inspection_number"),
                rs.getObject("reference_id", UUID.class),
                rs.getObject("lot_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("quantity_inspected"),
                rs.getBigDecimal("quantity_accepted"),
                rs.getBigDecimal("quantity_rejected"),
                rs.getString("result"),
                rs.getString("status"),
                rs.getString("notes"));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record LotRow(UUID id, UUID itemId, UUID storeId, BigDecimal quantity, String status,
                          BigDecimal unitCost, String batchRef, LocalDate expiryDate, UUID grnLineId) {}
}
