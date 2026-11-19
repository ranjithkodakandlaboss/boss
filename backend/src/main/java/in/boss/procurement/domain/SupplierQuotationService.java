package in.boss.procurement.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.masterdata.api.dto.SupplierDto;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.SupplierService;
import in.boss.procurement.api.ProcurementApiDtos.CreateSupplierQuotationRequest;
import in.boss.procurement.api.ProcurementApiDtos.PageResponse;
import in.boss.procurement.api.ProcurementApiDtos.ReceiveSupplierQuotationRequest;
import in.boss.procurement.api.ProcurementApiDtos.SupplierQuotationComparisonLine;
import in.boss.procurement.api.ProcurementApiDtos.SupplierQuotationComparisonOffer;
import in.boss.procurement.api.ProcurementApiDtos.SupplierQuotationComparisonResponse;
import in.boss.procurement.api.ProcurementApiDtos.SupplierQuotationLineRequest;
import in.boss.procurement.api.ProcurementApiDtos.SupplierQuotationLineResponse;
import in.boss.procurement.api.ProcurementApiDtos.SupplierQuotationResponse;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@PreAuthorize(BossPermissions.PROCUREMENT_MANAGE)
public class SupplierQuotationService {

    private static final Set<String> VALID_PR_STATUSES = Set.of("approved");

    private final JdbcTemplate jdbc;
    private final SupplierService supplierService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final SupplierQuotationDeliveryService deliveryService;

    public SupplierQuotationService(
            JdbcTemplate jdbc,
            SupplierService supplierService,
            AuditService auditService,
            ObjectMapper objectMapper,
            SupplierQuotationDeliveryService deliveryService) {
        this.jdbc = jdbc;
        this.deliveryService = deliveryService;
        this.supplierService = supplierService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<SupplierQuotationResponse> listByPr(UUID prId, int page, int size) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        validatePage(page, size);
        requirePr(tenantId, prId);

        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM supplier_quotations
                WHERE tenant_id = ? AND pr_id = ?
                """, Long.class, tenantId, prId);
        long total = count != null ? count : 0;
        List<UUID> ids = jdbc.query("""
                SELECT id FROM supplier_quotations
                WHERE tenant_id = ? AND pr_id = ?
                ORDER BY created_at DESC, id
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> rs.getObject("id", UUID.class),
                tenantId, prId, size, (long) page * size);
        List<SupplierQuotationResponse> content = ids.stream()
                .map(this::getQuotation)
                .toList();
        return new PageResponse<>(content, page, size, total);
    }

    @Transactional
    public SupplierQuotationResponse requestQuotation(UUID prId, CreateSupplierQuotationRequest request) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        PrHeader pr = requirePr(tenantId, prId);
        if (!VALID_PR_STATUSES.contains(pr.status())) {
            throw conflict("Supplier quotations can only be requested for an approved purchase requisition");
        }
        SupplierDto supplier = requireActiveSupplier(request.supplierId());
        List<UUID> existing = jdbc.query("""
                SELECT id FROM supplier_quotations
                WHERE tenant_id = ? AND pr_id = ? AND supplier_id = ? AND status = 'requested'
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), tenantId, prId, supplier.getId());
        if (!existing.isEmpty()) {
            deliveryService.send(existing.get(0));
            return getQuotation(existing.get(0));
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO supplier_quotations
                  (id, tenant_id, sq_number, pr_id, supplier_id, status, payment_terms, created_by)
                VALUES (?, ?, ?, ?, ?, 'requested', ?, ?)
                """,
                id, tenantId, nextDocumentNumber(), prId, supplier.getId(),
                supplier.getPaymentTerms(), context.userId());

        auditService.record("supplier_quotations", id, "REQUESTED", null, null);
        deliveryService.send(id);
        return getQuotation(id);
    }

    @Transactional(readOnly = true)
    public SupplierQuotationResponse getQuotation(UUID quotationId) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        List<QuotationHeader> headers = jdbc.query("""
                SELECT q.id, q.pr_id, q.supplier_id, s.name AS supplier_name,
                       q.status, q.valid_until, q.supplier_quote_ref, q.payment_terms, q.payment_terms_text,
                       q.freight_amount, q.discount_amount, q.tax_amount, q.selection_reason
                FROM supplier_quotations q
                JOIN suppliers s ON s.id = q.supplier_id AND s.tenant_id = q.tenant_id
                WHERE q.tenant_id = ? AND q.id = ?
                """, (rs, rowNum) -> new QuotationHeader(
                rs.getObject("id", UUID.class),
                rs.getObject("pr_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_name"),
                rs.getString("status"),
                rs.getObject("valid_until", LocalDate.class),
                rs.getString("supplier_quote_ref"),
                (Integer) rs.getObject("payment_terms"),
                rs.getString("payment_terms_text"),
                rs.getBigDecimal("freight_amount"),
                rs.getBigDecimal("discount_amount"),
                rs.getBigDecimal("tax_amount"),
                rs.getString("selection_reason")),
                tenantId, quotationId);
        if (headers.isEmpty()) {
            throw notFound("Supplier quotation not found: " + quotationId);
        }

        QuotationHeader header = headers.get(0);
        List<SupplierQuotationLineResponse> lines = jdbc.query("""
                SELECT ql.id, ql.pr_line_id, ql.item_id, ql.quantity, ql.uom_id, u.symbol AS uom_symbol,
                       ql.unit_price, ql.delivery_days, ql.tax_rate,
                       EXISTS (
                         SELECT 1
                         FROM spo_lines sl
                         JOIN spos so ON so.id = sl.spo_id AND so.tenant_id = sl.tenant_id
                         WHERE sl.tenant_id = ql.tenant_id
                           AND sl.sq_line_id = ql.id
                           AND so.status <> 'cancelled'
                       ) AS is_selected
                FROM supplier_quotation_lines ql
                JOIN units_of_measure u ON u.id = ql.uom_id AND u.tenant_id = ql.tenant_id
                WHERE ql.tenant_id = ? AND ql.sq_id = ?
                ORDER BY ql.line_no
                """, (rs, rowNum) -> new SupplierQuotationLineResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("pr_line_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("unit_price"),
                (Integer) rs.getObject("delivery_days"),
                rs.getBoolean("is_selected"),
                rs.getBigDecimal("tax_rate"),
                rs.getBigDecimal("quantity"),
                rs.getObject("uom_id", UUID.class),
                rs.getString("uom_symbol")),
                tenantId, quotationId);

        SupplierQuotationDeliveryService.Delivery delivery = deliveryService.latest(header.id());
        return new SupplierQuotationResponse(
                header.id(), header.prId(), header.supplierId(), header.supplierName(),
                header.status(), header.validUntil(), lines,
                header.supplierQuoteRef(), header.paymentTerms(), header.freightAmount(),
                header.discountAmount(), header.taxAmount(), header.selectionReason(),
                delivery.status(), delivery.recipient(), delivery.errorMessage(), header.paymentTermsText());
    }

    @Transactional
    public SupplierQuotationResponse receiveQuotation(
            UUID quotationId, ReceiveSupplierQuotationRequest request) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        QuotationHeader header = lockQuotation(tenantId, quotationId);
        requireStatus(header, "requested");
        requirePr(tenantId, header.prId());

        List<SupplierQuotationLineRequest> requestedLines = request.lines();
        if (requestedLines.stream().map(SupplierQuotationLineRequest::prLineId).distinct().count()
                != requestedLines.size()) {
            throw badRequest("A purchase requisition line may appear only once in a quotation");
        }

        int lineNo = 1;
        for (SupplierQuotationLineRequest requestedLine : requestedLines) {
            PrLine prLine = requirePrLine(tenantId, header.prId(), requestedLine.prLineId());
            jdbc.update("""
                    INSERT INTO supplier_quotation_lines
                      (tenant_id, sq_id, pr_line_id, line_no, item_id, quantity, uom_id,
                       unit_price, delivery_days, tax_rate)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    tenantId, quotationId, prLine.id(), lineNo++, prLine.itemId(),
                    requestedLine.quotedQty() != null ? requestedLine.quotedQty() : prLine.quantity(),
                    prLine.uomId(), requestedLine.unitPrice(),
                    requestedLine.leadTimeDays(), requestedLine.taxRate());
        }
        BigDecimal freight = request.freightAmount() == null ? BigDecimal.ZERO : request.freightAmount();
        BigDecimal discount = request.discountAmount() == null ? BigDecimal.ZERO : request.discountAmount();
        BigDecimal tax = lineTax(tenantId, quotationId);
        if (tax.signum() == 0 && request.taxAmount() != null) {
            tax = request.taxAmount();
        }

        jdbc.update("""
                UPDATE supplier_quotations
                SET status = 'received', valid_until = ?, supplier_quote_ref = ?,
                    payment_terms = COALESCE(?, payment_terms),
                    payment_terms_text = COALESCE(?, payment_terms_text),
                    freight_amount = ?, discount_amount = ?, tax_amount = ?, updated_at = NOW()
                WHERE tenant_id = ? AND id = ? AND status = 'requested'
                """, request.validUntil(), blank(request.supplierQuoteRef()), request.paymentTerms(),
                blank(request.paymentTermsText()), freight, discount, tax, tenantId, quotationId);

        SupplierQuotationResponse response = getQuotation(quotationId);
        auditService.record("supplier_quotations", quotationId, "RECEIVED",
                toJson(new StatusSnapshot("requested")), toJson(response));
        return response;
    }

    @Transactional
    public SupplierQuotationResponse startReview(UUID quotationId) {
        return transition(quotationId, "received", "under_review", "UNDER_REVIEW");
    }

    @Transactional
    public SupplierQuotationResponse selectQuotation(UUID quotationId, String reason) {
        SupplierQuotationResponse selected = transition(quotationId, "under_review", "selected", "SELECTED");
        if (reason != null && !reason.isBlank()) {
            jdbc.update("""
                    UPDATE supplier_quotations
                    SET selection_reason = ?, updated_at = NOW()
                    WHERE tenant_id = ? AND id = ?
                    """, reason.trim(), requireContext().tenantId(), quotationId);
            return getQuotation(quotationId);
        }
        return selected;
    }

    @Transactional
    public SupplierQuotationResponse rejectQuotation(UUID quotationId) {
        return transition(quotationId, "under_review", "rejected", "REJECTED");
    }

    @Transactional(readOnly = true)
    public SupplierQuotationComparisonResponse compareByPr(UUID prId) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        requirePr(tenantId, prId);

        Map<UUID, ComparisonAccumulator> lines = new LinkedHashMap<>();
        jdbc.query("""
                SELECT pl.id AS pr_line_id, pl.item_id, i.code AS item_code, i.name AS item_name,
                       pl.quantity, pl.uom_id, u.symbol AS uom_symbol,
                       q.id AS quotation_id, q.supplier_id, s.name AS supplier_name,
                       q.status, q.valid_until, ql.unit_price, ql.delivery_days,
                       ql.id AS quotation_line_id,
                       CASE WHEN ql.id IS NULL THEN FALSE ELSE EXISTS (
                         SELECT 1
                         FROM spo_lines sl
                         JOIN spos so ON so.id = sl.spo_id AND so.tenant_id = sl.tenant_id
                         WHERE sl.tenant_id = ql.tenant_id
                           AND sl.sq_line_id = ql.id
                           AND so.status <> 'cancelled'
                       ) END AS is_selected
                FROM pr_lines pl
                JOIN items i ON i.id = pl.item_id AND i.tenant_id = pl.tenant_id
                JOIN units_of_measure u ON u.id = pl.uom_id AND u.tenant_id = pl.tenant_id
                LEFT JOIN supplier_quotations q
                  ON q.tenant_id = pl.tenant_id AND q.pr_id = pl.pr_id
                LEFT JOIN supplier_quotation_lines ql
                  ON ql.tenant_id = q.tenant_id AND ql.sq_id = q.id AND ql.pr_line_id = pl.id
                LEFT JOIN suppliers s ON s.id = q.supplier_id AND s.tenant_id = q.tenant_id
                WHERE pl.tenant_id = ? AND pl.pr_id = ?
                ORDER BY pl.line_no, q.created_at, q.id
                """, rs -> {
            UUID prLineId = rs.getObject("pr_line_id", UUID.class);
            ComparisonAccumulator accumulator = lines.get(prLineId);
            if (accumulator == null) {
                accumulator = new ComparisonAccumulator(
                        prLineId,
                        rs.getObject("item_id", UUID.class),
                        rs.getString("item_code"),
                        rs.getString("item_name"),
                        rs.getBigDecimal("quantity"),
                        rs.getObject("uom_id", UUID.class),
                        rs.getString("uom_symbol"));
                lines.put(prLineId, accumulator);
            }
            UUID quotationLineId = rs.getObject("quotation_line_id", UUID.class);
            if (quotationLineId != null) {
                accumulator.offers().add(new SupplierQuotationComparisonOffer(
                        rs.getObject("quotation_id", UUID.class),
                        rs.getObject("supplier_id", UUID.class),
                        rs.getString("supplier_name"),
                        rs.getString("status"),
                        rs.getBigDecimal("unit_price"),
                        (Integer) rs.getObject("delivery_days"),
                        rs.getObject("valid_until", LocalDate.class),
                        rs.getBoolean("is_selected")));
            }
        }, tenantId, prId);

        List<SupplierQuotationComparisonLine> responseLines = lines.values().stream()
                .map(line -> new SupplierQuotationComparisonLine(
                        line.prLineId(), line.itemId(), line.itemCode(), line.itemName(),
                        line.quantity(), line.uomId(), line.uomSymbol(), List.copyOf(line.offers())))
                .toList();
        return new SupplierQuotationComparisonResponse(prId, responseLines);
    }

    private SupplierQuotationResponse transition(
            UUID quotationId, String expectedStatus, String newStatus, String action) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        QuotationHeader existing = lockQuotation(tenantId, quotationId);
        requireStatus(existing, expectedStatus);
        jdbc.update("""
                UPDATE supplier_quotations
                SET status = ?, updated_at = NOW()
                WHERE tenant_id = ? AND id = ? AND status = ?
                """, newStatus, tenantId, quotationId, expectedStatus);
        SupplierQuotationResponse response = getQuotation(quotationId);
        auditService.record("supplier_quotations", quotationId, action,
                toJson(new StatusSnapshot(expectedStatus)), toJson(response));
        return response;
    }

    private QuotationHeader lockQuotation(UUID tenantId, UUID quotationId) {
        List<QuotationHeader> headers = jdbc.query("""
                SELECT q.id, q.pr_id, q.supplier_id, s.name AS supplier_name, q.status, q.valid_until,
                       q.supplier_quote_ref, q.payment_terms, q.payment_terms_text, q.freight_amount, q.discount_amount,
                       q.tax_amount, q.selection_reason
                FROM supplier_quotations q
                JOIN suppliers s ON s.id = q.supplier_id AND s.tenant_id = q.tenant_id
                WHERE q.tenant_id = ? AND q.id = ?
                FOR UPDATE OF q
                """, (rs, rowNum) -> new QuotationHeader(
                rs.getObject("id", UUID.class),
                rs.getObject("pr_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_name"),
                rs.getString("status"),
                rs.getObject("valid_until", LocalDate.class),
                rs.getString("supplier_quote_ref"),
                (Integer) rs.getObject("payment_terms"),
                rs.getString("payment_terms_text"),
                rs.getBigDecimal("freight_amount"),
                rs.getBigDecimal("discount_amount"),
                rs.getBigDecimal("tax_amount"),
                rs.getString("selection_reason")),
                tenantId, quotationId);
        if (headers.isEmpty()) {
            throw notFound("Supplier quotation not found: " + quotationId);
        }
        return headers.get(0);
    }

    private PrHeader requirePr(UUID tenantId, UUID prId) {
        List<PrHeader> headers = jdbc.query("""
                SELECT id, status
                FROM purchase_requisitions
                WHERE tenant_id = ? AND id = ?
                """, (rs, rowNum) -> new PrHeader(
                rs.getObject("id", UUID.class), rs.getString("status")), tenantId, prId);
        if (headers.isEmpty()) {
            throw notFound("Purchase requisition not found: " + prId);
        }
        return headers.get(0);
    }

    private PrLine requirePrLine(UUID tenantId, UUID prId, UUID lineId) {
        List<PrLine> lines = jdbc.query("""
                SELECT id, item_id, quantity, uom_id
                FROM pr_lines
                WHERE tenant_id = ? AND pr_id = ? AND id = ?
                """, (rs, rowNum) -> new PrLine(
                rs.getObject("id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("quantity"),
                rs.getObject("uom_id", UUID.class)), tenantId, prId, lineId);
        if (lines.isEmpty()) {
            throw badRequest("Quotation line must reference a line in its purchase requisition");
        }
        return lines.get(0);
    }

    private SupplierDto requireActiveSupplier(UUID supplierId) {
        try {
            SupplierDto supplier = supplierService.getSupplier(supplierId);
            if (!"active".equalsIgnoreCase(supplier.getStatus())) {
                throw badRequest("Supplier must be active to request a quotation");
            }
            return supplier;
        } catch (EntityNotFoundException e) {
            throw notFound("Supplier not found: " + supplierId);
        }
    }

    private void requireStatus(QuotationHeader header, String expectedStatus) {
        if (!expectedStatus.equals(header.status())) {
            throw conflict("Quotation transition requires status " + expectedStatus
                    + "; current status is " + header.status());
        }
    }

    private BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null || context.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant user context is required");
        }
        return context;
    }

    private String nextDocumentNumber() {
        return "SQ-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
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
            throw new IllegalStateException("Could not serialize supplier quotation audit snapshot", e);
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

    private record PrHeader(UUID id, String status) {}

    private record PrLine(UUID id, UUID itemId, BigDecimal quantity, UUID uomId) {}

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BigDecimal lineTax(UUID tenantId, UUID quotationId) {
        BigDecimal tax = jdbc.queryForObject("""
                SELECT COALESCE(SUM(ROUND(quantity * unit_price * COALESCE(tax_rate, 0) / 100, 2)), 0)
                FROM supplier_quotation_lines
                WHERE tenant_id = ? AND sq_id = ? AND COALESCE(tax_rate, 0) > 0
                """, BigDecimal.class, tenantId, quotationId);
        return tax == null ? BigDecimal.ZERO : tax;
    }

    private record QuotationHeader(
            UUID id, UUID prId, UUID supplierId, String supplierName, String status, LocalDate validUntil,
            String supplierQuoteRef, Integer paymentTerms, String paymentTermsText, BigDecimal freightAmount,
            BigDecimal discountAmount, BigDecimal taxAmount, String selectionReason) {}

    private record StatusSnapshot(String status) {}

    private record ComparisonAccumulator(
            UUID prLineId,
            UUID itemId,
            String itemCode,
            String itemName,
            BigDecimal quantity,
            UUID uomId,
            String uomSymbol,
            List<SupplierQuotationComparisonOffer> offers) {
        private ComparisonAccumulator(
                UUID prLineId, UUID itemId, String itemCode, String itemName,
                BigDecimal quantity, UUID uomId, String uomSymbol) {
            this(prLineId, itemId, itemCode, itemName, quantity, uomId, uomSymbol, new ArrayList<>());
        }
    }
}
