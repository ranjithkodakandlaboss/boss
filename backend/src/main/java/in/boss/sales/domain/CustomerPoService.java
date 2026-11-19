package in.boss.sales.domain;

import in.boss.sales.api.SalesApiDtos.AcceptQuotationRequest;
import in.boss.sales.api.SalesApiDtos.CreateDirectCpoRequest;
import in.boss.sales.api.SalesApiDtos.CpoResponse;
import in.boss.sales.api.SalesApiDtos.PageResponse;
import in.boss.sales.api.SalesApiDtos.RecordMaterialAvailabilityRequest;
import in.boss.sales.api.SalesApiDtos.UpdateCpoRequest;
import in.boss.inventory.api.InventoryApiDtos.FeasibilityResponse;
import in.boss.inventory.domain.InventoryService;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import in.boss.shared.security.BossPermissions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@PreAuthorize(BossPermissions.SALES_ACCESS)
public class CustomerPoService {

    private static final Set<String> ACCEPTABLE = Set.of("sent", "negotiating");
    private static final Set<String> EDITABLE = Set.of("open", "on_hold");

    private final JdbcTemplate jdbc;
    private final CpoLookup cpoLookup;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final InventoryService inventoryService;

    public CustomerPoService(JdbcTemplate jdbc,
                             CpoLookup cpoLookup,
                             AuditService auditService,
                             ObjectMapper objectMapper,
                             InventoryService inventoryService) {
        this.jdbc = jdbc;
        this.cpoLookup = cpoLookup;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.inventoryService = inventoryService;
    }

    public record AcceptResult(CpoResponse cpo, boolean created) {}

    @Transactional
    public AcceptResult accept(UUID quotationId, AcceptQuotationRequest request) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        QuotationLock quotation = lockQuotation(tenantId, quotationId);
        CpoResponse existing = cpoLookup.findByQuotation(tenantId, quotationId);
        if (existing != null || "accepted".equals(quotation.status())) {
            if (existing == null) {
                throw conflict("Quotation is already accepted");
            }
            return new AcceptResult(existing, false);
        }
        if (!ACCEPTABLE.contains(quotation.status())) {
            throw conflict("Only a sent or negotiating quotation can be accepted");
        }
        if (quotation.validUntil() != null && quotation.validUntil().isBefore(LocalDate.now())) {
            throw conflict("Quotation validity has passed. Revise it before recording acceptance.");
        }
        String note = request == null ? null : request.confirmationNote();
        String customerPoRef = request == null ? null : request.customerPoRef();
        UUID cpoId = UUID.randomUUID();
        String cpoNumber = "CPO-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("""
                INSERT INTO cpos
                  (id, tenant_id, cpo_number, quotation_id, customer_id, site_id, status,
                   customer_po_ref, order_date, required_by_date, payment_terms, payment_terms_text, notes,
                   discount_amount, source_revision, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'open', ?, CURRENT_DATE, ?, ?, ?, ?, ?, ?, ?)
                """,
                cpoId, tenantId, cpoNumber, quotationId, quotation.customerId(), quotation.siteId(),
                customerPoRef, quotation.validUntil(), quotation.paymentTerms(),
                jdbc.queryForObject("SELECT payment_terms_text FROM quotations WHERE tenant_id = ? AND id = ?",
                        String.class, tenantId, quotationId),
                note, quotation.discountAmount(), quotation.revision(), context.userId());
        copyLines(tenantId, quotationId, cpoId);
        jdbc.update("""
                UPDATE quotations
                   SET status = 'accepted', decision_channel = 'OWNER_RECORDED', decision_note = ?,
                       decided_by = ?, decided_at = NOW(), updated_at = NOW()
                 WHERE tenant_id = ? AND id = ?
                """,
                note, context.userId(), tenantId, quotationId);
        CpoResponse created = cpoLookup.read(tenantId, cpoId);
        Map<String, String> beforeDecision = new LinkedHashMap<>();
        beforeDecision.put("status", quotation.status());
        Map<String, String> afterDecision = new LinkedHashMap<>();
        afterDecision.put("status", "accepted");
        afterDecision.put("decisionNote", note == null ? "" : note);
        afterDecision.put("customerPoRef", customerPoRef == null ? "" : customerPoRef);
        afterDecision.put("cpoNumber", cpoNumber);
        jdbc.update("""
                UPDATE rfqs SET status = 'closed', updated_at = NOW()
                WHERE tenant_id = ? AND id = (SELECT rfq_id FROM quotations WHERE tenant_id = ? AND id = ?)
                  AND status <> 'cancelled'
                """, tenantId, tenantId, quotationId);
        auditService.record("QUOTATION", quotationId, "OWNER_ACCEPTANCE", toJson(beforeDecision), toJson(afterDecision));
        auditService.record("CPO", cpoId, "CREATE_FROM_ACCEPTANCE", null, toJson(created));
        return new AcceptResult(created, true);
    }

    @Transactional(readOnly = true)
    public PageResponse<CpoResponse> list(UUID customerId, UUID quotationId, String status, String query,
                                          int page, int size) {
        return cpoLookup.list(customerId, quotationId, status, query, page, size);
    }

    @Transactional(readOnly = true)
    public CpoResponse get(UUID cpoId) {
        return cpoLookup.get(cpoId);
    }

    @Transactional
    public CpoResponse update(UUID cpoId, UpdateCpoRequest request) {
        UUID tenantId = requireContext().tenantId();
        String status = lockStatus(tenantId, cpoId);
        if (!EDITABLE.contains(status)) {
            throw conflict("Customer PO commercial fields can only be edited while it is open or on hold");
        }
        CpoResponse before = cpoLookup.read(tenantId, cpoId);
        jdbc.update("""
                UPDATE cpos
                   SET customer_po_ref = ?, notes = ?, required_by_date = ?,
                       payment_terms = COALESCE(?, payment_terms), updated_at = NOW()
                 WHERE tenant_id = ? AND id = ?
                """,
                request.customerPoRef(), request.notes(), request.requiredByDate(), request.paymentTerms(),
                tenantId, cpoId);
        CpoResponse result = cpoLookup.read(tenantId, cpoId);
        auditService.record("CPO", cpoId, "UPDATE", toJson(before), toJson(result));
        return result;
    }

    @Transactional
    @PreAuthorize(BossPermissions.CPO_TRANSITIONS)
    public CpoResponse updateStatus(UUID cpoId, String nextStatus) {
        return transitionStatus(cpoId, nextStatus, true);
    }

    @Transactional
    @PreAuthorize(BossPermissions.CPO_TRANSITIONS)
    public FeasibilityResponse checkFeasibility(UUID cpoId) {
        UUID tenantId = requireContext().tenantId();
        String current = lockStatus(tenantId, cpoId);
        if (!Set.of("open", "partial", "feasibility_pending").contains(current)) {
            throw conflict("Feasibility can only be run for an open or pending Customer PO");
        }

        if ("open".equals(current) || "partial".equals(current)) {
            transitionStatus(cpoId, "feasibility_pending", false);
        }
        FeasibilityResponse feasibility = inventoryService.checkAndReserveForCustomerPo(cpoId);
        if (feasibility.feasible()) {
            transitionStatus(cpoId, "feasibility_approved", false);
        }
        return feasibility;
    }

    private CpoResponse transitionStatus(UUID cpoId, String nextStatus, boolean reserveForApproval) {
        UUID tenantId = requireContext().tenantId();
        String current = lockStatus(tenantId, cpoId);
        if (current.equals(nextStatus)
                && Set.of("feasibility_pending", "feasibility_approved", "cancelled").contains(current)) {
            return cpoLookup.read(tenantId, cpoId);
        }
        boolean allowed = (Set.of("open", "on_hold", "partial", "feasibility_pending",
                        "feasibility_approved").contains(current) && "cancelled".equals(nextStatus))
                || ("open".equals(current) && "on_hold".equals(nextStatus))
                || ("on_hold".equals(current) && "open".equals(nextStatus))
                || ("open".equals(current) && "feasibility_pending".equals(nextStatus))
                || ("partial".equals(current) && "feasibility_pending".equals(nextStatus))
                || ("feasibility_pending".equals(current) && "feasibility_pending".equals(nextStatus))
                || ("feasibility_pending".equals(current) && "feasibility_approved".equals(nextStatus));
        if (!allowed) {
            throw conflict("Invalid Customer PO status transition: " + current + " -> " + nextStatus);
        }
        CpoResponse before = cpoLookup.read(tenantId, cpoId);
        if ("feasibility_approved".equals(nextStatus) && reserveForApproval) {
            var feasibility = inventoryService.checkAndReserveForCustomerPo(cpoId);
            if (!feasibility.feasible()) {
                throw conflict("Customer PO cannot be approved because required materials are unavailable");
            }
        }
        jdbc.update("UPDATE cpos SET status = ?, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                nextStatus, tenantId, cpoId);
        if ("cancelled".equals(nextStatus)) {
            inventoryService.releaseCustomerPoReservations(cpoId);
        }
        CpoResponse result = cpoLookup.read(tenantId, cpoId);
        auditService.record("CPO", cpoId, "STATUS_" + nextStatus.toUpperCase(), toJson(before), toJson(result));
        return result;
    }

    @Transactional
    @PreAuthorize(BossPermissions.CPO_TRANSITIONS)
    public CpoResponse createDirect(CreateDirectCpoRequest request) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        UUID cpoId = UUID.randomUUID();
        String cpoNumber = "CPO-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("""
                INSERT INTO cpos
                  (id, tenant_id, cpo_number, customer_id, site_id, status,
                   customer_po_ref, order_date, payment_terms, direct_order_reason, delivery_terms, created_by)
                VALUES (?, ?, ?, ?, ?, 'open', ?, CURRENT_DATE, ?, ?, ?, ?)
                """,
                cpoId, tenantId, cpoNumber, request.customerId(), request.siteId(),
                request.customerPoRef(), request.paymentTerms(),
                request.directOrderReason(), request.deliveryTerms(), context.userId());
        int lineNo = 1;
        for (CreateDirectCpoRequest.DirectCpoLine line : request.lines()) {
            jdbc.update("""
                    INSERT INTO cpo_lines
                      (id, tenant_id, cpo_id, line_no, item_id, quantity, uom_id,
                       unit_price, gst_rate, drawing_number, description)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), tenantId, cpoId, lineNo++, line.itemId(),
                    line.quantity(), line.uomId(), line.unitPrice(),
                    line.gstRate() != null ? line.gstRate() : BigDecimal.ZERO,
                    line.drawingNumber(), line.description());
        }
        CpoResponse created = cpoLookup.read(tenantId, cpoId);
        auditService.record("CPO", cpoId, "CREATE_DIRECT", null, toJson(created));
        return created;
    }

    private void copyLines(UUID tenantId, UUID quotationId, UUID cpoId) {
        List<SourceLine> lines = jdbc.query("""
                SELECT id, line_no, item_id, quantity, uom_id, unit_price, gst_rate, discount_pct
                  FROM quotation_lines
                 WHERE tenant_id = ? AND quotation_id = ?
                 ORDER BY line_no
                """, (rs, rowNum) -> new SourceLine(
                rs.getObject("id", UUID.class),
                rs.getInt("line_no"),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("quantity"),
                rs.getObject("uom_id", UUID.class),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("gst_rate"),
                rs.getBigDecimal("discount_pct")), tenantId, quotationId);
        for (SourceLine line : lines) {
            jdbc.update("""
                    INSERT INTO cpo_lines
                      (id, tenant_id, cpo_id, quotation_line_id, line_no, item_id, quantity, uom_id,
                       unit_price, gst_rate, discount_pct)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), tenantId, cpoId, line.id(), line.lineNo(), line.itemId(),
                    line.quantity(), line.uomId(), line.unitPrice(), line.gstRate(), line.discountPct());
        }
    }

    private QuotationLock lockQuotation(UUID tenantId, UUID quotationId) {
        List<QuotationLock> rows = jdbc.query("""
                SELECT status, customer_id, site_id, valid_until, payment_terms, discount_amount, revision
                  FROM quotations
                 WHERE tenant_id = ? AND id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new QuotationLock(
                rs.getString("status"),
                rs.getObject("customer_id", UUID.class),
                rs.getObject("site_id", UUID.class),
                localDate(rs, "valid_until"),
                (Integer) rs.getObject("payment_terms"),
                rs.getBigDecimal("discount_amount"),
                rs.getInt("revision")), tenantId, quotationId);
        if (rows.isEmpty()) {
            throw notFound("Quotation not found");
        }
        return rows.get(0);
    }

    private String lockStatus(UUID tenantId, UUID cpoId) {
        List<CpoStatusRow> rows = jdbc.query(
                "SELECT status, site_id FROM cpos WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> new CpoStatusRow(rs.getString("status"),
                        rs.getObject("site_id", UUID.class)), tenantId, cpoId);
        if (rows.isEmpty() || !requireContext().canAccessSite(rows.get(0).siteId())) {
            throw notFound("Customer PO not found");
        }
        return rows.get(0).status();
    }

    @Transactional
    public CpoResponse recordAvailability(UUID cpoId, RecordMaterialAvailabilityRequest request) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        String status = lockStatus(tenantId, cpoId);
        if (!EDITABLE.contains(status)) {
            throw conflict("Material availability can be recorded only while the Customer PO is open or on hold");
        }
        for (RecordMaterialAvailabilityRequest.AvailabilityLine decision : request.lines()) {
            BigDecimal ordered = jdbc.query("""
                    SELECT quantity FROM cpo_lines
                    WHERE tenant_id = ? AND cpo_id = ? AND id = ?
                    FOR UPDATE
                    """, rs -> rs.next() ? rs.getBigDecimal("quantity") : null,
                    tenantId, cpoId, decision.lineId());
            if (ordered == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Availability line is not on this Customer PO");
            }
            BigDecimal shortage = switch (decision.availability()) {
                case "available" -> BigDecimal.ZERO;
                case "unavailable" -> ordered;
                default -> decision.shortageQty();
            };
            if (shortage == null || shortage.signum() < 0 || shortage.compareTo(ordered) > 0
                    || ("partial".equals(decision.availability())
                    && (shortage.signum() == 0 || shortage.compareTo(ordered) >= 0))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Partial availability needs a shortage greater than zero and less than the ordered quantity");
            }
            jdbc.update("""
                    UPDATE cpo_lines
                    SET availability = ?, shortage_qty = ?
                    WHERE tenant_id = ? AND cpo_id = ? AND id = ?
                    """, decision.availability(), shortage, tenantId, cpoId, decision.lineId());
        }
        auditService.record("cpos", cpoId, "AVAILABILITY", null, toJson(request));
        return cpoLookup.get(cpoId);
    }

    private BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null || context.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant user context is required");
        }
        return context;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize Customer PO audit snapshot", e);
        }
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record QuotationLock(String status, UUID customerId, UUID siteId, LocalDate validUntil,
                                 Integer paymentTerms, BigDecimal discountAmount, int revision) {}

    private record CpoStatusRow(String status, UUID siteId) {}

    private record SourceLine(UUID id, int lineNo, UUID itemId, BigDecimal quantity, UUID uomId,
                              BigDecimal unitPrice, BigDecimal gstRate, BigDecimal discountPct) {}
}
