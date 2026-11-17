package in.boss.procurement.domain;

import in.boss.inventory.api.InventoryApiDtos.CreateLotRequest;
import in.boss.inventory.domain.InventoryService;
import in.boss.procurement.api.ProcurementApiDtos.CreateGrnLineRequest;
import in.boss.procurement.api.ProcurementApiDtos.CreateGrnLotRequest;
import in.boss.procurement.api.ProcurementApiDtos.CreateGrnRequest;
import in.boss.procurement.api.ProcurementApiDtos.GrnLineResponse;
import in.boss.procurement.api.ProcurementApiDtos.GrnResponse;
import in.boss.procurement.api.ProcurementApiDtos.PageResponse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@PreAuthorize("hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER','PURCHASE_MANAGER')")
public class GoodsReceiptService {

    private static final Set<String> RECEIVABLE = Set.of("issued", "acknowledged", "partially_received");

    private final JdbcTemplate jdbc;
    private final InventoryService inventoryService;
    private final PurchaseRequisitionService prService;
    private final AuditService auditService;

    public GoodsReceiptService(JdbcTemplate jdbc,
                               InventoryService inventoryService,
                               PurchaseRequisitionService prService,
                               AuditService auditService) {
        this.jdbc = jdbc;
        this.inventoryService = inventoryService;
        this.prService = prService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<GrnResponse> list(UUID spoId, int page, int size) {
        BossContext context = requireContext();
        if (page < 0 || size < 1 || size > 100) {
            throw badRequest("Page must be non-negative and size must be between 1 and 100");
        }
        String filter = spoId == null ? "" : " AND g.spo_id = ?";
        Object[] args = spoId == null ? new Object[] {context.tenantId()} : new Object[] {context.tenantId(), spoId};
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM grns g WHERE g.tenant_id = ?" + filter, Long.class, args);
        List<Object> pageArgs = new ArrayList<>(List.of(args));
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<UUID> ids = jdbc.query("""
                SELECT g.id FROM grns g
                WHERE g.tenant_id = ?
                """ + filter + """
                 ORDER BY g.created_at DESC
                 LIMIT ? OFFSET ?
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), pageArgs.toArray());
        return new PageResponse<>(ids.stream().map(this::get).toList(), page, size, count == null ? 0 : count);
    }

    @Transactional(readOnly = true)
    public GrnResponse get(UUID grnId) {
        BossContext context = requireContext();
        List<GrnResponse> headers = jdbc.query("""
                SELECT g.id, g.grn_number, g.spo_id, o.spo_number, g.supplier_id, s.name,
                       g.site_id, g.status, g.received_date, g.supplier_dc_ref, g.vehicle_number
                FROM grns g
                JOIN suppliers s ON s.id = g.supplier_id AND s.tenant_id = g.tenant_id
                LEFT JOIN spos o ON o.id = g.spo_id AND o.tenant_id = g.tenant_id
                WHERE g.tenant_id = ? AND g.id = ?
                """, (rs, rowNum) -> new GrnResponse(
                rs.getObject("id", UUID.class),
                rs.getString("grn_number"),
                rs.getObject("spo_id", UUID.class),
                rs.getString("spo_number"),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("name"),
                rs.getObject("site_id", UUID.class),
                rs.getString("status"),
                rs.getObject("received_date", LocalDate.class),
                rs.getString("supplier_dc_ref"),
                rs.getString("vehicle_number"),
                List.of()), context.tenantId(), grnId);
        if (headers.isEmpty()) {
            throw notFound("Goods receipt not found");
        }
        GrnResponse header = headers.get(0);
        List<GrnLineResponse> lines = jdbc.query("""
                SELECT l.id, l.spo_line_id, l.item_id, i.code, i.name, l.received_qty,
                       l.accepted_qty, l.rejected_qty, u.symbol
                FROM grn_lines l
                JOIN items i ON i.id = l.item_id AND i.tenant_id = l.tenant_id
                JOIN units_of_measure u ON u.id = l.uom_id AND u.tenant_id = l.tenant_id
                WHERE l.tenant_id = ? AND l.grn_id = ?
                ORDER BY l.line_no
                """, (rs, rowNum) -> {
            UUID lineId = rs.getObject("id", UUID.class);
            List<UUID> lots = new ArrayList<>();
            List<String> lotNumbers = new ArrayList<>();
            jdbc.query("""
                    SELECT DISTINCT m.lot_id, l.lot_number
                    FROM stock_movements m
                    JOIN material_lots l ON l.id = m.lot_id AND l.tenant_id = m.tenant_id
                    WHERE m.tenant_id = ? AND m.reference_type = 'GRN' AND m.reference_id = ?
                      AND m.movement_type = 'receipt' AND m.lot_id IS NOT NULL
                    """, rsLot -> {
                lots.add(rsLot.getObject("lot_id", UUID.class));
                lotNumbers.add(rsLot.getString("lot_number"));
            }, context.tenantId(), lineId);
            return new GrnLineResponse(
                    lineId,
                    rs.getObject("spo_line_id", UUID.class),
                    rs.getObject("item_id", UUID.class),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getBigDecimal("received_qty"),
                    rs.getBigDecimal("accepted_qty"),
                    rs.getBigDecimal("rejected_qty"),
                    rs.getString("symbol"),
                    lots,
                    lotNumbers);
        }, context.tenantId(), grnId);
        return new GrnResponse(header.id(), header.grnNumber(), header.spoId(), header.spoNumber(),
                header.supplierId(), header.supplierName(), header.siteId(), header.status(),
                header.receivedDate(), header.supplierDcRef(), header.vehicleNumber(), lines);
    }

    @Transactional
    public GrnResponse receive(CreateGrnRequest request) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        SpoRow spo = lockSpo(tenantId, request.spoId());
        if (!RECEIVABLE.contains(spo.status())) {
            throw conflict("Goods can be received only against an issued, acknowledged, or partially received order");
        }
        UUID siteId = jdbc.query("""
                SELECT site_id FROM stores WHERE tenant_id = ? AND id = ?
                """, rs -> rs.next() ? rs.getObject("site_id", UUID.class) : null, tenantId, request.storeId());
        if (siteId == null || !siteId.equals(spo.siteId())) {
            throw badRequest("The receiving store does not belong to the supplier order site");
        }
        UUID grnId = UUID.randomUUID();
        String grnNumber = "GRN-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + grnId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO grns
                  (id, tenant_id, grn_number, spo_id, supplier_id, site_id, status,
                   received_date, supplier_dc_ref, vehicle_number, notes, received_by)
                VALUES (?, ?, ?, ?, ?, ?, 'draft', ?, ?, ?, ?, ?)
                """, grnId, tenantId, grnNumber, spo.id(), spo.supplierId(), siteId,
                request.receivedDate(), request.deliveryChallan().trim(),
                blank(request.vehicleNumber()), blank(request.notes()), context.userId());

        int lineNo = 1;
        BigDecimal ordered = BigDecimal.ZERO;
        BigDecimal acceptedTotal = BigDecimal.ZERO;
        BigDecimal rejectedTotal = BigDecimal.ZERO;
        for (CreateGrnLineRequest line : request.lines()) {
            SpoLineRow spoLine = lockLine(tenantId, spo.id(), line.spoLineId());
            BigDecimal rejected = line.rejectedQty() == null ? BigDecimal.ZERO : line.rejectedQty();
            if (rejected.compareTo(line.receivedQty()) > 0) {
                throw badRequest("Rejected quantity cannot exceed the quantity received");
            }
            BigDecimal accepted = line.receivedQty().subtract(rejected);
            BigDecimal already = spoLine.receivedQty();
            if (already.add(accepted).compareTo(spoLine.orderedQty()) > 0) {
                throw conflict("Receipt exceeds the quantity still open on the supplier order. No over-receipt tolerance is configured.");
            }
            BigDecimal lotQty = line.lots().stream().map(CreateGrnLotRequest::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (lotQty.compareTo(accepted) != 0) {
                throw badRequest("Lot quantities must equal the accepted quantity");
            }
            UUID grnLineId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO grn_lines
                      (id, tenant_id, grn_id, spo_line_id, line_no, item_id, received_qty,
                       accepted_qty, rejected_qty, uom_id, unit_price, store_id, lot_number, notes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, grnLineId, tenantId, grnId, spoLine.id(), lineNo++, spoLine.itemId(),
                    line.receivedQty(), accepted, rejected, spoLine.uomId(), spoLine.unitPrice(),
                    request.storeId(), line.lots().get(0).lotNumber(), blank(line.notes()));
            for (CreateGrnLotRequest lot : line.lots()) {
                inventoryService.createLot(new CreateLotRequest(
                        spoLine.itemId(), request.storeId(), lot.qty(), spoLine.unitPrice(),
                        lot.supplierLotRef() == null ? lot.lotNumber() : lot.supplierLotRef(),
                        lot.expiryDate(), "available", grnLineId));
            }
            jdbc.update("""
                    UPDATE spo_lines SET received_qty = received_qty + ?, store_id = ?
                    WHERE tenant_id = ? AND id = ?
                    """, accepted, request.storeId(), tenantId, spoLine.id());
            ordered = ordered.add(spoLine.orderedQty());
            acceptedTotal = acceptedTotal.add(accepted);
            rejectedTotal = rejectedTotal.add(rejected);
        }

        boolean complete = jdbc.queryForObject("""
                SELECT COUNT(*) = 0 FROM spo_lines
                WHERE tenant_id = ? AND spo_id = ? AND received_qty < quantity
                """, Boolean.class, tenantId, spo.id());
        String spoStatus = Boolean.TRUE.equals(complete) ? "fully_received" : "partially_received";
        jdbc.update("""
                UPDATE spos SET status = ?, updated_at = NOW()
                WHERE tenant_id = ? AND id = ? AND status = ?
                """, spoStatus, tenantId, spo.id(), spo.status());
        String grnStatus = rejectedTotal.signum() == 0 ? "accepted" : "partially_accepted";
        jdbc.update("UPDATE grns SET status = ?, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                grnStatus, tenantId, grnId);
        jdbc.update("""
                INSERT INTO supplier_performance_records
                  (tenant_id, supplier_id, spo_id, grn_id, promised_date, actual_date,
                   quantity_ordered, quantity_received, quantity_rejected, quality_pass)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, spo.supplierId(), spo.id(), grnId, spo.expectedDelivery(),
                request.receivedDate(), ordered, acceptedTotal, rejectedTotal, rejectedTotal.signum() == 0);
        if (spo.prId() != null) {
            prService.recalculateCoverage(spo.prId());
        }
        GrnResponse response = get(grnId);
        auditService.record("grns", grnId, "RECEIVED", null,
                "{\"status\":\"" + grnStatus + "\",\"stockStatus\":\"closed\"}");
        return response;
    }

    private SpoRow lockSpo(UUID tenantId, UUID spoId) {
        List<SpoRow> rows = jdbc.query("""
                SELECT id, supplier_id, site_id, pr_id, status, expected_delivery
                FROM spos WHERE tenant_id = ? AND id = ? FOR UPDATE
                """, (rs, rowNum) -> new SpoRow(
                rs.getObject("id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getObject("site_id", UUID.class),
                rs.getObject("pr_id", UUID.class),
                rs.getString("status"),
                rs.getObject("expected_delivery", LocalDate.class)), tenantId, spoId);
        if (rows.isEmpty()) {
            throw notFound("Supplier purchase order not found");
        }
        return rows.get(0);
    }

    private SpoLineRow lockLine(UUID tenantId, UUID spoId, UUID lineId) {
        List<SpoLineRow> rows = jdbc.query("""
                SELECT id, item_id, quantity, received_qty, uom_id, unit_price
                FROM spo_lines
                WHERE tenant_id = ? AND spo_id = ? AND id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new SpoLineRow(
                rs.getObject("id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("received_qty"),
                rs.getObject("uom_id", UUID.class),
                rs.getBigDecimal("unit_price")), tenantId, spoId, lineId);
        if (rows.isEmpty()) {
            throw badRequest("Receipt line is not on this supplier purchase order");
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

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    private record SpoRow(UUID id, UUID supplierId, UUID siteId, UUID prId, String status, LocalDate expectedDelivery) {}

    private record SpoLineRow(UUID id, UUID itemId, BigDecimal orderedQty, BigDecimal receivedQty,
                              UUID uomId, BigDecimal unitPrice) {}
}
