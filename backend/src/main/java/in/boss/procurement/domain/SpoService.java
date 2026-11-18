package in.boss.procurement.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.masterdata.api.dto.SupplierDto;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.SupplierService;
import in.boss.procurement.api.ProcurementApiDtos.AmendSpoRequest;
import in.boss.procurement.api.ProcurementApiDtos.CreateSpoLineRequest;
import in.boss.procurement.api.ProcurementApiDtos.DeclineSpoRequest;
import in.boss.procurement.api.ProcurementApiDtos.SpoAmendmentResponse;
import in.boss.procurement.api.ProcurementApiDtos.CreateSpoRequest;
import in.boss.procurement.api.ProcurementApiDtos.PageResponse;
import in.boss.procurement.api.ProcurementApiDtos.SpoLineResponse;
import in.boss.procurement.api.ProcurementApiDtos.SpoResponse;
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
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@PreAuthorize(BossPermissions.PROCUREMENT_MANAGE)
public class SpoService {

    private static final Set<String> VALID_STATUSES =
            Set.of("draft", "issued", "acknowledged", "declined", "partially_received", "fully_received", "cancelled");
    private static final Set<String> PR_STATUSES_ACCEPTING_ORDERS = Set.of("approved", "partially_ordered");

    private final JdbcTemplate jdbc;
    private final SupplierService supplierService;
    private final PurchaseRequisitionService prService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public SpoService(
            JdbcTemplate jdbc,
            SupplierService supplierService,
            PurchaseRequisitionService prService,
            AuditService auditService,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.supplierService = supplierService;
        this.prService = prService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<SpoResponse> listSpos(UUID supplierId, String status, int page, int size) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        validatePage(page, size);
        String normalizedStatus = normalizeStatus(status);

        StringBuilder where = new StringBuilder(" WHERE tenant_id = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(tenantId);
        if (supplierId != null) {
            where.append(" AND supplier_id = ?");
            parameters.add(supplierId);
        }
        if (normalizedStatus != null) {
            where.append(" AND status = ?");
            parameters.add(normalizedStatus);
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM spos" + where, Long.class, parameters.toArray());
        long total = count != null ? count : 0;
        where.append(' ');
        List<Object> pagedParameters = new ArrayList<>(parameters);
        pagedParameters.add(size);
        pagedParameters.add((long) page * size);
        List<UUID> ids = jdbc.query("""
                SELECT id FROM spos
                """ + where + """
                ORDER BY created_at DESC, id
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), pagedParameters.toArray());
        List<SpoResponse> content = ids.stream().map(this::getSpo).toList();
        return new PageResponse<>(content, page, size, total);
    }

    @Transactional
    public SpoResponse createSpo(CreateSpoRequest request) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        if (request.lines().stream().map(CreateSpoLineRequest::prLineId).distinct().count()
                != request.lines().size()) {
            throw badRequest("A purchase requisition line may appear only once in an SPO");
        }

        SelectedQuotation quotation = requireSelectedQuotation(
                tenantId, request.supplierQuotationId(), request.prId(), request.supplierId());
        if (!PR_STATUSES_ACCEPTING_ORDERS.contains(quotation.prStatus())) {
            throw conflict("SPOs can only be created for approved or partially ordered purchase requisitions");
        }
        requireActiveSupplier(request.supplierId());

        List<ValidatedSpoLine> validatedLines = new ArrayList<>();
        for (CreateSpoLineRequest line : request.lines().stream()
                .sorted(Comparator.comparing(CreateSpoLineRequest::prLineId))
                .toList()) {
            ValidatedSpoLine validated = requireMatchingQuotationLine(
                    tenantId, quotation, line);
            BigDecimal committedQty = committedQuantity(tenantId, request.prId(), line.prLineId());
            if (committedQty.add(line.orderedQty()).compareTo(validated.prQuantity()) > 0) {
                throw badRequest("SPO quantity exceeds the remaining approved PR quantity for line "
                        + line.prLineId());
            }
            if (line.orderedQty().compareTo(validated.quotationQuantity()) > 0) {
                throw badRequest("SPO quantity exceeds the quantity on the selected quotation for line "
                        + line.prLineId());
            }
            validatedLines.add(validated.withRequest(line));
        }

        UUID id = UUID.randomUUID();
        String spoNumber = nextDocumentNumber();
        jdbc.update("""
                INSERT INTO spos
                  (id, tenant_id, spo_number, sq_id, pr_id, supplier_id, site_id, status,
                   order_date, payment_terms, payment_terms_text, freight_amount, tax_amount, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'draft', ?, ?, ?, ?, ?, ?)
                """,
                id, tenantId, spoNumber, quotation.id(), quotation.prId(), quotation.supplierId(),
                quotation.siteId(), LocalDate.now(), quotation.paymentTerms(), quotation.paymentTermsText(),
                quotation.freightAmount() == null ? BigDecimal.ZERO : quotation.freightAmount(),
                quotation.taxAmount() == null ? BigDecimal.ZERO : quotation.taxAmount(), context.userId());

        int lineNo = 1;
        for (ValidatedSpoLine line : validatedLines) {
            CreateSpoLineRequest requestLine = line.request();
            jdbc.update("""
                    INSERT INTO spo_lines
                      (tenant_id, spo_id, sq_line_id, pr_line_id, line_no, item_id,
                       quantity, uom_id, unit_price, gst_rate, received_qty, expected_date)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                    """,
                    tenantId, id, line.quotationLineId(), line.prLineId(), lineNo++,
                    line.itemId(), requestLine.orderedQty(), line.uomId(), line.unitPrice(),
                    line.taxRate(), requestLine.expectedDeliveryDate());
        }

        SpoResponse response = getSpo(id);
        auditService.record("spos", id, "CREATED", null, toJson(response));
        return response;
    }

    @Transactional(readOnly = true)
    public SpoResponse getSpo(UUID spoId) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        List<SpoHeader> headers = jdbc.query("""
                SELECT o.id, o.spo_number, o.pr_id, o.sq_id, o.supplier_id,
                       s.name AS supplier_name, o.status, o.created_at, o.revision, o.decline_reason,
                       o.payment_terms, o.payment_terms_text, o.freight_amount, o.tax_amount
                FROM spos o
                JOIN suppliers s ON s.id = o.supplier_id AND s.tenant_id = o.tenant_id
                WHERE o.tenant_id = ? AND o.id = ?
                """, (rs, rowNum) -> mapHeader(rs),
                tenantId, spoId);
        if (headers.isEmpty()) {
            throw notFound("SPO not found: " + spoId);
        }

        SpoHeader header = headers.get(0);
        List<SpoLineResponse> lines = jdbc.query("""
                SELECT sl.id, sl.item_id, i.code AS item_code, i.name AS item_name,
                       sl.quantity, sl.unit_price, sl.expected_date, sl.gst_rate,
                       COALESCE((SELECT SUM(g.received_qty) FROM grn_lines g
                                 WHERE g.tenant_id = sl.tenant_id AND g.spo_line_id = sl.id), sl.received_qty) AS received_qty,
                       COALESCE((SELECT SUM(g.accepted_qty) FROM grn_lines g
                                 WHERE g.tenant_id = sl.tenant_id AND g.spo_line_id = sl.id), 0) AS accepted_qty,
                       COALESCE((SELECT SUM(g.rejected_qty) FROM grn_lines g
                                 WHERE g.tenant_id = sl.tenant_id AND g.spo_line_id = sl.id), 0) AS rejected_qty
                FROM spo_lines sl
                JOIN items i ON i.id = sl.item_id AND i.tenant_id = sl.tenant_id
                WHERE sl.tenant_id = ? AND sl.spo_id = ?
                ORDER BY sl.line_no
                """, (rs, rowNum) -> mapLine(rs), tenantId, spoId);
        BigDecimal goods = lines.stream().map(SpoLineResponse::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lineTax = lines.stream()
                .map(line -> line.gstRate() == null ? BigDecimal.ZERO
                        : line.lineTotal().multiply(line.gstRate()).movePointLeft(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tax = lineTax.signum() > 0 ? lineTax : (header.taxAmount() == null ? BigDecimal.ZERO : header.taxAmount());
        BigDecimal freight = header.freightAmount() == null ? BigDecimal.ZERO : header.freightAmount();
        BigDecimal total = goods.add(tax).add(freight).setScale(2, RoundingMode.HALF_UP);
        return new SpoResponse(
                header.id(), header.spoNumber(), header.prId(), header.quotationId(),
                header.supplierId(), header.supplierName(), header.status(), lines,
                total, header.createdAt(), header.revision(), header.declineReason(),
                header.paymentTerms(), header.paymentTermsText(), freight, tax);
    }

    @Transactional
    public SpoResponse issueSpo(UUID spoId) {
        return transition(spoId, "draft", "issued", "ISSUED");
    }

    @Transactional
    public SpoResponse acknowledgeSpo(UUID spoId) {
        return transition(spoId, "issued", "acknowledged", "ACKNOWLEDGED");
    }

    @Transactional
    public SpoResponse cancelSpo(UUID spoId) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        SpoHeader existing = lockSpo(tenantId, spoId);
        if (!Set.of("draft", "issued", "acknowledged", "partially_received").contains(existing.status())) {
            throw conflict("SPO cannot be cancelled from status " + existing.status());
        }
        updateStatus(tenantId, spoId, existing.status(), "cancelled");
        SpoResponse response = getSpo(spoId);
        auditService.record("spos", spoId, "CANCELLED",
                toJson(new StatusSnapshot(existing.status())), toJson(response));
        prService.recalculateCoverage(existing.prId());
        return response;
    }

    @Transactional
    public SpoResponse declineSpo(UUID spoId, DeclineSpoRequest request) {
        BossContext context = requireContext();
        SpoHeader existing = lockSpo(context.tenantId(), spoId);
        if (!"issued".equals(existing.status())) {
            throw conflict("A supplier can decline only an issued purchase order");
        }
        jdbc.update("""
                UPDATE spos
                SET status = 'declined', decline_reason = ?, updated_at = NOW()
                WHERE tenant_id = ? AND id = ? AND status = 'issued'
                """, request.reason().trim(), context.tenantId(), spoId);
        SpoResponse response = getSpo(spoId);
        auditService.record("spos", spoId, "DECLINED",
                toJson(new StatusSnapshot(existing.status())), toJson(response));
        prService.recalculateCoverage(existing.prId());
        return response;
    }

    @Transactional(readOnly = true)
    public List<SpoAmendmentResponse> listAmendments(UUID spoId) {
        BossContext context = requireContext();
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spos WHERE tenant_id = ? AND id = ?",
                Integer.class, context.tenantId(), spoId);
        if (found == null || found == 0) {
            throw notFound("SPO not found: " + spoId);
        }
        return jdbc.query("""
                SELECT id, spo_id, revision, reason, change_note, status
                FROM spo_amendments
                WHERE tenant_id = ? AND spo_id = ?
                ORDER BY revision
                """, (rs, rowNum) -> new SpoAmendmentResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("spo_id", UUID.class),
                rs.getInt("revision"),
                rs.getString("reason"),
                rs.getString("change_note"),
                rs.getString("status")), context.tenantId(), spoId);
    }

    @Transactional
    public SpoAmendmentResponse submitAmendment(UUID spoId, AmendSpoRequest request) {
        BossContext context = requireContext();
        SpoHeader existing = lockSpo(context.tenantId(), spoId);
        if (!Set.of("draft", "issued", "acknowledged").contains(existing.status())) {
            throw conflict("This supplier purchase order can no longer be amended");
        }
        Integer pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM spo_amendments
                WHERE tenant_id = ? AND spo_id = ? AND status = 'pending_approval'
                """, Integer.class, context.tenantId(), spoId);
        if (pending != null && pending > 0) {
            throw conflict("An amendment is already waiting for approval");
        }
        int revision = existing.revision() + 1;
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO spo_amendments
                  (id, tenant_id, spo_id, revision, reason, change_note, line_id,
                   quantity, unit_price, expected_date, payment_terms, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending_approval', ?)
                """, id, context.tenantId(), spoId, revision, request.reason().trim(),
                request.changeNote().trim(), request.lineId(), request.quantity(), request.unitPrice(),
                request.expectedDate(), request.paymentTerms(), context.userId());
        auditService.record("spo_amendments", id, "SUBMITTED", null, toJson(request));
        return new SpoAmendmentResponse(id, spoId, revision, request.reason(), request.changeNote(), "pending_approval");
    }

    @Transactional
    public SpoResponse decideAmendment(UUID spoId, UUID amendmentId, boolean approve) {
        BossContext context = requireContext();
        if (!context.hasRole("OWNER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can decide a supplier PO amendment");
        }
        SpoHeader existing = lockSpo(context.tenantId(), spoId);
        List<AmendSpoRequest> rows = jdbc.query("""
                SELECT reason, change_note, line_id, quantity, unit_price, expected_date, payment_terms
                FROM spo_amendments
                WHERE tenant_id = ? AND spo_id = ? AND id = ? AND status = 'pending_approval'
                FOR UPDATE
                """, (rs, rowNum) -> new AmendSpoRequest(
                rs.getString("reason"), rs.getString("change_note"),
                rs.getObject("line_id", UUID.class), rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_price"), rs.getObject("expected_date", LocalDate.class),
                (Integer) rs.getObject("payment_terms")), context.tenantId(), spoId, amendmentId);
        if (rows.isEmpty()) {
            throw notFound("Pending amendment not found");
        }
        if (!approve) {
            jdbc.update("""
                    UPDATE spo_amendments SET status = 'rejected'
                    WHERE tenant_id = ? AND id = ?
                    """, context.tenantId(), amendmentId);
            auditService.record("spo_amendments", amendmentId, "REJECTED", null, null);
            return getSpo(spoId);
        }
        AmendSpoRequest change = rows.get(0);
        if (change.lineId() != null && (change.quantity() != null || change.unitPrice() != null || change.expectedDate() != null)) {
            int updated = jdbc.update("""
                    UPDATE spo_lines
                    SET quantity = COALESCE(?, quantity),
                        unit_price = COALESCE(?, unit_price),
                        expected_date = COALESCE(?, expected_date)
                    WHERE tenant_id = ? AND spo_id = ? AND id = ?
                      AND COALESCE(?, quantity) >= received_qty
                    """, change.quantity(), change.unitPrice(), change.expectedDate(),
                    context.tenantId(), spoId, change.lineId(), change.quantity());
            if (updated == 0) {
                throw conflict("The amended quantity cannot be below the quantity already received");
            }
        }
        jdbc.update("""
                UPDATE spos
                SET revision = revision + 1,
                    payment_terms = COALESCE(?, payment_terms),
                    expected_delivery = COALESCE(?, expected_delivery),
                    updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """, change.paymentTerms(), change.expectedDate(), context.tenantId(), spoId);
        jdbc.update("""
                UPDATE spo_amendments SET status = 'applied'
                WHERE tenant_id = ? AND id = ?
                """, context.tenantId(), amendmentId);
        SpoResponse response = getSpo(spoId);
        auditService.record("spos", spoId, "AMENDED",
                toJson(new StatusSnapshot(existing.status())), toJson(response));
        return response;
    }

    private SpoResponse transition(UUID spoId, String expectedStatus, String newStatus, String action) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        SpoHeader existing = lockSpo(tenantId, spoId);
        if (!expectedStatus.equals(existing.status())) {
            throw conflict("SPO transition requires status " + expectedStatus
                    + "; current status is " + existing.status());
        }
        if ("issued".equals(newStatus)) {
            String prStatus = requirePrStatus(tenantId, existing.prId());
            if (!PR_STATUSES_ACCEPTING_ORDERS.contains(prStatus)) {
                throw conflict("SPO cannot be issued because its purchase requisition is " + prStatus);
            }
        }
        updateStatus(tenantId, spoId, expectedStatus, newStatus);
        SpoResponse response = getSpo(spoId);
        auditService.record("spos", spoId, action,
                toJson(new StatusSnapshot(expectedStatus)), toJson(response));
        if ("issued".equals(newStatus)) {
            prService.recalculateCoverage(existing.prId());
        }
        return response;
    }

    private SelectedQuotation requireSelectedQuotation(
            UUID tenantId, UUID quotationId, UUID prId, UUID supplierId) {
        List<SelectedQuotation> quotations = jdbc.query("""
                SELECT q.id, q.pr_id, q.supplier_id, q.status, q.payment_terms, q.payment_terms_text,
                       q.freight_amount, q.tax_amount, p.site_id, p.status AS pr_status
                FROM supplier_quotations q
                JOIN purchase_requisitions p
                  ON p.id = q.pr_id AND p.tenant_id = q.tenant_id
                WHERE q.tenant_id = ? AND q.id = ?
                FOR UPDATE OF q, p
                """, (rs, rowNum) -> new SelectedQuotation(
                rs.getObject("id", UUID.class),
                rs.getObject("pr_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("status"),
                (Integer) rs.getObject("payment_terms"),
                rs.getString("payment_terms_text"),
                rs.getBigDecimal("freight_amount"),
                rs.getBigDecimal("tax_amount"),
                rs.getObject("site_id", UUID.class),
                rs.getString("pr_status")), tenantId, quotationId);
        if (quotations.isEmpty()) {
            throw notFound("Selected supplier quotation not found: " + quotationId);
        }
        SelectedQuotation quotation = quotations.get(0);
        if (!"selected".equals(quotation.status())) {
            throw conflict("SPO must be created from a selected supplier quotation");
        }
        if (!prId.equals(quotation.prId()) || !supplierId.equals(quotation.supplierId())) {
            throw badRequest("SPO purchase requisition and supplier must match the selected quotation");
        }
        return quotation;
    }

    private ValidatedSpoLine requireMatchingQuotationLine(
            UUID tenantId, SelectedQuotation quotation, CreateSpoLineRequest request) {
        List<ValidatedSpoLine> lines = jdbc.query("""
                SELECT ql.id AS quotation_line_id, ql.pr_line_id, ql.item_id,
                       ql.quantity AS quotation_quantity, ql.uom_id, ql.unit_price, ql.tax_rate,
                       pl.quantity AS pr_quantity, pl.item_id AS pr_item_id, pl.uom_id AS pr_uom_id
                FROM supplier_quotation_lines ql
                JOIN pr_lines pl
                  ON pl.id = ql.pr_line_id AND pl.tenant_id = ql.tenant_id
                WHERE ql.tenant_id = ? AND ql.sq_id = ? AND ql.pr_line_id = ?
                FOR UPDATE OF ql, pl
                """, (rs, rowNum) -> new ValidatedSpoLine(
                rs.getObject("quotation_line_id", UUID.class),
                rs.getObject("pr_line_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("quotation_quantity"),
                rs.getObject("uom_id", UUID.class),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("pr_quantity"),
                rs.getObject("pr_item_id", UUID.class),
                rs.getObject("pr_uom_id", UUID.class),
                rs.getBigDecimal("tax_rate"),
                null), tenantId, quotation.id(), request.prLineId());
        if (lines.isEmpty()) {
            throw badRequest("Every SPO line must match a line in the selected supplier quotation");
        }
        if (lines.size() != 1) {
            throw conflict("Selected quotation contains duplicate lines for a purchase requisition line");
        }
        ValidatedSpoLine line = lines.get(0);
        if (!line.itemId().equals(request.itemId()) || !line.itemId().equals(line.prItemId())
                || !line.uomId().equals(request.uomId()) || !line.uomId().equals(line.prUomId())) {
            throw badRequest("SPO item and unit of measure must match the selected quotation and PR line");
        }
        if (line.unitPrice().compareTo(request.unitPrice()) != 0) {
            throw badRequest("SPO unit price must match the selected quotation");
        }
        return line;
    }

    private BigDecimal committedQuantity(UUID tenantId, UUID prId, UUID prLineId) {
        BigDecimal quantity = jdbc.queryForObject("""
                SELECT COALESCE(SUM(sl.quantity), 0)
                FROM spo_lines sl
                JOIN spos o ON o.id = sl.spo_id AND o.tenant_id = sl.tenant_id
                WHERE sl.tenant_id = ? AND o.pr_id = ? AND sl.pr_line_id = ?
                  AND o.status NOT IN ('cancelled', 'declined')
                """, BigDecimal.class, tenantId, prId, prLineId);
        return quantity != null ? quantity : BigDecimal.ZERO;
    }

    private String requirePrStatus(UUID tenantId, UUID prId) {
        List<String> statuses = jdbc.query("""
                SELECT status FROM purchase_requisitions WHERE tenant_id = ? AND id = ?
                """, (rs, rowNum) -> rs.getString("status"), tenantId, prId);
        if (statuses.isEmpty()) {
            throw notFound("Purchase requisition not found: " + prId);
        }
        return statuses.get(0);
    }

    private SpoHeader lockSpo(UUID tenantId, UUID spoId) {
        List<SpoHeader> headers = jdbc.query("""
                SELECT o.id, o.spo_number, o.pr_id, o.sq_id, o.supplier_id,
                       s.name AS supplier_name, o.status, o.created_at, o.revision, o.decline_reason,
                       o.payment_terms, o.payment_terms_text, o.freight_amount, o.tax_amount
                FROM spos o
                JOIN suppliers s ON s.id = o.supplier_id AND s.tenant_id = o.tenant_id
                WHERE o.tenant_id = ? AND o.id = ?
                FOR UPDATE OF o
                """, (rs, rowNum) -> mapHeader(rs),
                tenantId, spoId);
        if (headers.isEmpty()) {
            throw notFound("SPO not found: " + spoId);
        }
        return headers.get(0);
    }

    private void updateStatus(UUID tenantId, UUID spoId, String oldStatus, String newStatus) {
        int updated = jdbc.update("""
                UPDATE spos SET status = ?, updated_at = NOW()
                WHERE tenant_id = ? AND id = ? AND status = ?
                """, newStatus, tenantId, spoId, oldStatus);
        if (updated != 1) {
            throw conflict("SPO status changed concurrently; reload and retry");
        }
    }

    private void requireActiveSupplier(UUID supplierId) {
        try {
            SupplierDto supplier = supplierService.getSupplier(supplierId);
            if (!"active".equalsIgnoreCase(supplier.getStatus())) {
                throw badRequest("Supplier must be active to create an SPO");
            }
        } catch (EntityNotFoundException e) {
            throw notFound("Supplier not found: " + supplierId);
        }
    }

    private SpoLineResponse mapLine(ResultSet rs) throws SQLException {
        BigDecimal quantity = rs.getBigDecimal("quantity");
        BigDecimal unitPrice = rs.getBigDecimal("unit_price");
        BigDecimal lineTotal = quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
        return new SpoLineResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getString("item_code"),
                rs.getString("item_name"),
                quantity,
                rs.getBigDecimal("received_qty"),
                unitPrice,
                lineTotal,
                rs.getObject("expected_date", LocalDate.class),
                rs.getBigDecimal("accepted_qty"),
                rs.getBigDecimal("rejected_qty"),
                rs.getBigDecimal("gst_rate"));
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        if (!VALID_STATUSES.contains(normalized)) {
            throw badRequest("Invalid SPO status: " + status);
        }
        return normalized;
    }

    private BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null || context.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant user context is required");
        }
        return context;
    }

    private String nextDocumentNumber() {
        return "SPO-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw badRequest("Page must be non-negative and size must be between 1 and 100");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize SPO audit snapshot", e);
        }
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record SelectedQuotation(
            UUID id, UUID prId, UUID supplierId, String status, Integer paymentTerms,
            String paymentTermsText, BigDecimal freightAmount, BigDecimal taxAmount,
            UUID siteId, String prStatus) {}

    private record ValidatedSpoLine(
            UUID quotationLineId,
            UUID prLineId,
            UUID itemId,
            BigDecimal quotationQuantity,
            UUID uomId,
            BigDecimal unitPrice,
            BigDecimal prQuantity,
            UUID prItemId,
            UUID prUomId,
            BigDecimal taxRate,
            CreateSpoLineRequest request) {
        private ValidatedSpoLine withRequest(CreateSpoLineRequest request) {
            return new ValidatedSpoLine(
                    quotationLineId, prLineId, itemId, quotationQuantity, uomId,
                    unitPrice, prQuantity, prItemId, prUomId, taxRate, request);
        }
    }

    private SpoHeader mapHeader(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SpoHeader(
                rs.getObject("id", UUID.class),
                rs.getString("spo_number"),
                rs.getObject("pr_id", UUID.class),
                rs.getObject("sq_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_name"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getInt("revision"),
                rs.getString("decline_reason"),
                (Integer) rs.getObject("payment_terms"),
                rs.getString("payment_terms_text"),
                rs.getBigDecimal("freight_amount"),
                rs.getBigDecimal("tax_amount"));
    }

    private record SpoHeader(
            UUID id, String spoNumber, UUID prId, UUID quotationId, UUID supplierId,
            String supplierName, String status, java.time.Instant createdAt,
            int revision, String declineReason, Integer paymentTerms, String paymentTermsText,
            BigDecimal freightAmount, BigDecimal taxAmount) {}

    private record StatusSnapshot(String status) {}
}
