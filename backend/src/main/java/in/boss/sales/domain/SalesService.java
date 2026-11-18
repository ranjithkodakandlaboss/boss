package in.boss.sales.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.masterdata.api.dto.CustomerDto;
import in.boss.masterdata.api.dto.ItemDto;
import in.boss.masterdata.api.dto.UomDto;
import in.boss.masterdata.domain.CustomerService;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.ItemService;
import in.boss.masterdata.domain.UomService;
import in.boss.sales.api.SalesApiDtos.AuditEntry;
import in.boss.sales.api.SalesApiDtos.FieldChange;
import in.boss.sales.api.SalesApiDtos.CreateQuotationLineRequest;
import in.boss.sales.api.SalesApiDtos.CreateQuotationRequest;
import in.boss.sales.api.SalesApiDtos.CreateRfqRequest;
import in.boss.sales.api.SalesApiDtos.CounterOfferRequest;
import in.boss.sales.api.SalesApiDtos.NegotiationEntryResponse;
import in.boss.sales.api.SalesApiDtos.OfferedUnitPrice;
import in.boss.sales.api.SalesApiDtos.PageResponse;
import in.boss.sales.api.SalesApiDtos.QuotationLineResponse;
import in.boss.sales.api.SalesApiDtos.QuotationCostingSnapshot;
import in.boss.sales.api.SalesApiDtos.QuotationResponse;
import in.boss.sales.api.SalesApiDtos.RevisionLink;
import in.boss.sales.api.SalesApiDtos.ReviseQuotationRequest;
import in.boss.sales.api.SalesApiDtos.RfqLineRequest;
import in.boss.sales.api.SalesApiDtos.RfqLineResponse;
import in.boss.sales.api.SalesApiDtos.RfqResponse;
import in.boss.sales.api.SalesApiDtos.UpdateQuotationRequest;
import in.boss.sales.api.SalesApiDtos.UpdateRfqRequest;
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
import java.time.Instant;
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
@PreAuthorize(BossPermissions.SALES_ACCESS)
public class SalesService {

    private static final Set<String> RFQ_STATUSES = Set.of("draft", "sent", "closed", "cancelled");
    private static final Set<String> QUOTATION_STATUSES_NOT_REVISABLE =
            Set.of("accepted", "superseded", "expired");

    private final JdbcTemplate jdbc;
    private final CustomerService customerService;
    private final ItemService itemService;
    private final UomService uomService;
    private final QuotationCostingService quotationCostingService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public SalesService(JdbcTemplate jdbc,
                        CustomerService customerService,
                        ItemService itemService,
                        UomService uomService,
                        QuotationCostingService quotationCostingService,
                        AuditService auditService,
                        ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.customerService = customerService;
        this.itemService = itemService;
        this.uomService = uomService;
        this.quotationCostingService = quotationCostingService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RfqResponse createRfq(CreateRfqRequest request) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        UUID siteId = requireActiveSite(context);
        requireCustomer(request.customerId());

        UUID rfqId = UUID.randomUUID();
        String rfqNumber = nextDocumentNumber("RFQ");
        LocalDate received = request.receivedDate() != null ? request.receivedDate() : LocalDate.now();
        jdbc.update("""
                INSERT INTO rfqs
                  (id, tenant_id, rfq_number, customer_id, site_id, status, received_date,
                   required_by_date, rfq_type_id, rfq_type_label, customer_ref, notes,
                   delivery_terms, payment_terms, received_via, created_by, technical_requirements)
                VALUES (?, ?, ?, ?, ?, 'draft', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                rfqId, tenantId, rfqNumber, request.customerId(), siteId, received, request.requiredDate(),
                request.rfqTypeId(), request.rfqTypeLabel(), request.customerRef(), request.notes(),
                request.deliveryTerms(), request.paymentTerms(), request.receivedVia(), context.userId(),
                jsonOrNull(request.technicalRequirements()));
        replaceRfqLines(tenantId, rfqId, request.lines());

        RfqResponse result = readRfq(tenantId, rfqId);
        auditService.record("RFQ", rfqId, "CREATE", null, toJson(result));
        return result;
    }

    @Transactional(readOnly = true)
    public PageResponse<RfqResponse> listRfqs(UUID customerId, String status, int page, int size) {
        return listRfqs(customerId, status, null, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<RfqResponse> listRfqs(UUID customerId, String status, String query, int page, int size) {
        UUID tenantId = requireContext().tenantId();
        validatePage(page, size);
        if (status != null && !RFQ_STATUSES.contains(status)) {
            throw badRequest("Unsupported RFQ status");
        }

        StringBuilder where = new StringBuilder(" WHERE r.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (customerId != null) {
            where.append(" AND r.customer_id = ?");
            args.add(customerId);
        }
        if (status != null) {
            where.append(" AND r.status = ?");
            args.add(status);
        }
        if (query != null && !query.isBlank()) {
            where.append(" AND (r.rfq_number ILIKE ? OR c.name ILIKE ? OR COALESCE(r.customer_ref, '') ILIKE ?)");
            String like = "%" + query.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        String from = " FROM rfqs r JOIN customers c ON c.tenant_id = r.tenant_id AND c.id = r.customer_id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<UUID> ids = jdbc.query(
                "SELECT r.id" + from + where + " ORDER BY r.created_at DESC, r.id LIMIT ? OFFSET ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class), args.toArray());
        List<RfqResponse> content = ids.stream().map(id -> readRfq(tenantId, id)).toList();
        return new PageResponse<>(content, page, size, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public RfqResponse getRfq(UUID rfqId) {
        return readRfq(requireContext().tenantId(), rfqId);
    }

    @Transactional
    public RfqResponse updateDraftRfq(UUID rfqId, UpdateRfqRequest request) {
        UUID tenantId = requireContext().tenantId();
        String status = rfqStatus(tenantId, rfqId);
        if (!"draft".equals(status)) {
            throw conflict("RFQ lines can only be edited while the RFQ is draft");
        }
        RfqResponse before = readRfq(tenantId, rfqId);
        jdbc.update("""
                UPDATE rfqs
                   SET required_by_date = ?, rfq_type_id = ?, rfq_type_label = ?,
                       customer_ref = ?, notes = ?, delivery_terms = ?, payment_terms = ?,
                       received_via = ?, received_date = COALESCE(?, received_date),
                       technical_requirements = ?::jsonb, updated_at = NOW()
                 WHERE tenant_id = ? AND id = ?
                """,
                request.requiredDate(), request.rfqTypeId(), request.rfqTypeLabel(),
                request.customerRef(), request.notes(), request.deliveryTerms(), request.paymentTerms(),
                request.receivedVia(), request.receivedDate(), jsonOrNull(request.technicalRequirements()), tenantId, rfqId);
        jdbc.update("DELETE FROM rfq_lines WHERE tenant_id = ? AND rfq_id = ?", tenantId, rfqId);
        replaceRfqLines(tenantId, rfqId, request.lines());

        RfqResponse result = readRfq(tenantId, rfqId);
        auditService.record("RFQ", rfqId, "UPDATE", toJson(before), toJson(result));
        return result;
    }

    @Transactional
    public RfqResponse updateRfqStatus(UUID rfqId, String nextStatus) {
        UUID tenantId = requireContext().tenantId();
        String current = rfqStatus(tenantId, rfqId);
        boolean validTransition = ("draft".equals(current) && Set.of("sent", "cancelled").contains(nextStatus))
                || ("sent".equals(current) && Set.of("closed", "cancelled").contains(nextStatus));
        if (!validTransition) {
            throw conflict("Invalid RFQ status transition: " + current + " -> " + nextStatus);
        }
        RfqResponse before = readRfq(tenantId, rfqId);
        jdbc.update("UPDATE rfqs SET status = ?, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                nextStatus, tenantId, rfqId);
        RfqResponse result = readRfq(tenantId, rfqId);
        auditService.record("RFQ", rfqId, "STATUS_" + nextStatus.toUpperCase(), toJson(before), toJson(result));
        return result;
    }

    @Transactional
    public QuotationResponse createQuotation(CreateQuotationRequest request) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        UUID siteId = requireActiveSite(context);
        requireCustomer(request.customerId());
        if (request.rfqId() != null) {
            UUID rfqCustomer = jdbc.query(
                    "SELECT customer_id FROM rfqs WHERE tenant_id = ? AND id = ?",
                    rs -> rs.next() ? rs.getObject("customer_id", UUID.class) : null,
                    tenantId, request.rfqId());
            if (rfqCustomer == null) {
                throw notFound("RFQ not found");
            }
            if (!request.customerId().equals(rfqCustomer)) {
                throw badRequest("Quotation customer must match the linked RFQ customer");
            }
            String rfqStatus = jdbc.queryForObject(
                    "SELECT status FROM rfqs WHERE tenant_id = ? AND id = ?", String.class, tenantId, request.rfqId());
            if ("closed".equals(rfqStatus) || "cancelled".equals(rfqStatus)) {
                throw conflict("This RFQ is already closed and cannot start another quotation");
            }
        }

        UUID quotationId = UUID.randomUUID();
        String quotationNumber = nextDocumentNumber("QT");
        jdbc.update("""
                INSERT INTO quotations
                  (id, tenant_id, quotation_number, rfq_id, customer_id, site_id, revision,
                   status, valid_until, payment_terms, payment_terms_text, delivery_terms, notes, discount_amount, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 1, 'draft', ?, COALESCE(?, 30), ?, ?, ?, COALESCE(?, 0), ?)
                """,
                quotationId, tenantId, quotationNumber, request.rfqId(), request.customerId(),
                siteId, request.validUntil(), request.paymentTerms(), blank(request.paymentTermsText()),
                request.deliveryTerms(), request.notes(), request.discountAmount(), context.userId());
        BigDecimal estimatedTotal = replaceQuotationLines(tenantId, quotationId, request.lines());
        jdbc.update("UPDATE quotations SET estimated_material_cost = ? WHERE tenant_id = ? AND id = ?",
                estimatedTotal, tenantId, quotationId);
        saveCostingSnapshot(tenantId, quotationId, request.lines(), request.discountAmount());

        QuotationResponse result = readQuotation(tenantId, quotationId);
        auditService.record("QUOTATION", quotationId, "CREATE", null, toJson(result));
        return result;
    }

    @Transactional(readOnly = true)
    public QuotationResponse getQuotation(UUID quotationId) {
        return readQuotation(requireContext().tenantId(), quotationId);
    }

    @Transactional(readOnly = true)
    public byte[] quotationPdf(UUID quotationId) {
        UUID tenantId = requireContext().tenantId();
        QuotationResponse quotation = readQuotation(tenantId, quotationId);
        return QuotationPdf.render(quotation, seller(tenantId), buyer(tenantId, quotation.customerId()));
    }

    @Transactional(readOnly = true)
    public PageResponse<QuotationResponse> listQuotations(
            UUID customerId, UUID rfqId, String status, String query, int page, int size) {
        UUID tenantId = requireContext().tenantId();
        validatePage(page, size);
        StringBuilder where = new StringBuilder(" WHERE q.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (customerId != null) {
            where.append(" AND q.customer_id = ?");
            args.add(customerId);
        }
        if (rfqId != null) {
            where.append(" AND q.rfq_id = ?");
            args.add(rfqId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND q.status = ?");
            args.add(status);
        }
        if (query != null && !query.isBlank()) {
            where.append(" AND (q.quotation_number ILIKE ? OR c.name ILIKE ? OR COALESCE(r.rfq_number, '') ILIKE ?)");
            String like = "%" + query.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        String from = """
                 FROM quotations q
                 JOIN customers c ON c.tenant_id = q.tenant_id AND c.id = q.customer_id
                 LEFT JOIN rfqs r ON r.tenant_id = q.tenant_id AND r.id = q.rfq_id
                """;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<UUID> ids = jdbc.query(
                "SELECT q.id" + from + where + " ORDER BY q.created_at DESC, q.revision DESC, q.id LIMIT ? OFFSET ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class), args.toArray());
        List<QuotationResponse> content = ids.stream().map(id -> readQuotation(tenantId, id)).toList();
        return new PageResponse<>(content, page, size, total == null ? 0 : total);
    }

    @Transactional
    public QuotationResponse updateDraftQuotation(UUID quotationId, UpdateQuotationRequest request) {
        UUID tenantId = requireContext().tenantId();
        String status = quotationStatus(tenantId, quotationId);
        if (!"draft".equals(status)) {
            throw conflict("Only a draft quotation can be edited. Revise a sent quotation instead.");
        }
        QuotationResponse before = readQuotation(tenantId, quotationId);
        jdbc.update("""
                UPDATE quotations
                   SET valid_until = ?, payment_terms = COALESCE(?, payment_terms),
                       payment_terms_text = COALESCE(?, payment_terms_text),
                       delivery_terms = ?, notes = ?, discount_amount = COALESCE(?, discount_amount),
                       updated_at = NOW()
                 WHERE tenant_id = ? AND id = ?
                """,
                request.validUntil(), request.paymentTerms(), blank(request.paymentTermsText()),
                request.deliveryTerms(), request.notes(), request.discountAmount(), tenantId, quotationId);
        jdbc.update("DELETE FROM quotation_lines WHERE tenant_id = ? AND quotation_id = ?", tenantId, quotationId);
        BigDecimal estimatedTotal = replaceQuotationLines(tenantId, quotationId, request.lines());
        jdbc.update("UPDATE quotations SET estimated_material_cost = ? WHERE tenant_id = ? AND id = ?",
                estimatedTotal, tenantId, quotationId);
        saveCostingSnapshot(tenantId, quotationId, request.lines(),
                request.discountAmount() == null ? before.discountAmount() : request.discountAmount());
        QuotationResponse result = readQuotation(tenantId, quotationId);
        auditService.record("QUOTATION", quotationId, "UPDATE", toJson(before), toJson(result));
        return result;
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> listHistory(String entityType, UUID entityId) {
        UUID tenantId = requireContext().tenantId();
        if (!Set.of("CUSTOMER", "RFQ", "QUOTATION", "CPO").contains(entityType)) {
            throw badRequest("Unsupported history type");
        }
        if (!historySubjectExists(tenantId, entityType, entityId)) {
            throw notFound("Record not found");
        }
        return jdbc.query("""
                SELECT e.id, e.actor_id, COALESCE(u.display_name, u.email) AS actor_name,
                       e.action, e.created_at, e.old_data::text AS old_data, e.new_data::text AS new_data
                  FROM audit_events e
                  LEFT JOIN app_users u ON u.id = e.actor_id AND u.tenant_id = e.tenant_id
                 WHERE e.tenant_id = ? AND e.entity_type = ? AND e.entity_id = ?
                 ORDER BY e.created_at DESC, e.id DESC
                """, (rs, rowNum) -> new AuditEntry(
                rs.getObject("id", UUID.class),
                rs.getObject("actor_id", UUID.class),
                rs.getString("actor_name"),
                rs.getString("action"),
                instant(rs, "created_at"),
                fieldChanges(rs.getString("old_data"), rs.getString("new_data"))),
                tenantId, entityType, entityId);
    }

    private boolean historySubjectExists(UUID tenantId, String entityType, UUID entityId) {
        String table = switch (entityType) {
            case "CUSTOMER" -> "customers";
            case "RFQ" -> "rfqs";
            case "QUOTATION" -> "quotations";
            case "CPO" -> "cpos";
            default -> throw badRequest("Unsupported history type");
        };
        return count("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ? AND id = ?", tenantId, entityId) > 0;
    }

    @Transactional
    public QuotationResponse updateQuotationStatus(UUID quotationId, String nextStatus) {
        return updateQuotationStatus(quotationId, nextStatus, null);
    }

    @Transactional
    public QuotationResponse updateQuotationStatus(UUID quotationId, String nextStatus, String note) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        String current = quotationStatus(tenantId, quotationId);
        validateQuotationTransition(current, nextStatus);
        if ("sent".equals(nextStatus)) {
            validateQuotationSubmission(context, tenantId, quotationId, note);
        }
        QuotationResponse before = readQuotation(tenantId, quotationId);
        updateQuotationStatusRecord(context, tenantId, quotationId, nextStatus, note, before);
        QuotationResponse result = readQuotation(tenantId, quotationId);
        auditService.record("QUOTATION", quotationId, "STATUS_" + nextStatus.toUpperCase(), toJson(before), toJson(result));
        return result;
    }

    private void validateQuotationTransition(String current, String nextStatus) {
        boolean valid = ("draft".equals(current) && "sent".equals(nextStatus))
                || (Set.of("sent", "negotiating").contains(current)
                && Set.of("rejected", "expired").contains(nextStatus));
        if (!valid) {
            throw conflict("Invalid quotation status transition: " + current + " -> " + nextStatus);
        }
    }

    private void validateQuotationSubmission(BossContext context, UUID tenantId,
                                             UUID quotationId, String note) {
        Integer missingDrawing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM quotation_lines
                WHERE tenant_id = ? AND quotation_id = ?
                  AND (drawing_number IS NULL OR btrim(drawing_number) = '')
                """, Integer.class, tenantId, quotationId);
        if (missingDrawing != null && missingDrawing > 0) {
            throw badRequest("Drawing number is required on every line before the quotation is submitted");
        }
        QuotationCostingSnapshot costing = readQuotation(tenantId, quotationId).costing();
        if (costing == null || !costing.available()) {
            throw conflict("A complete quotation cost estimate is required before submission");
        }
        if (costing.requiresApproval()) {
            validateMarginOverride(context, note);
        }
    }

    private static void validateMarginOverride(BossContext context, String note) {
        if (!context.hasAnyRole("OWNER", "OPS_MANAGER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "An owner or operations manager must approve a below-threshold quotation");
        }
        if (note == null || note.isBlank()) {
            throw badRequest("A reason is required to approve a below-threshold quotation");
        }
    }

    private void updateQuotationStatusRecord(BossContext context, UUID tenantId, UUID quotationId,
                                             String nextStatus, String note, QuotationResponse before) {
        if ("rejected".equals(nextStatus)) {
            jdbc.update("""
                    UPDATE quotations
                       SET status = ?, decision_channel = 'OWNER_RECORDED', decision_note = ?,
                           decided_by = ?, decided_at = NOW(), updated_at = NOW()
                     WHERE tenant_id = ? AND id = ?
                    """,
                    nextStatus, note, context.userId(), tenantId, quotationId);
        } else if (requiresMarginOverride(nextStatus, before)) {
            jdbc.update("""
                    UPDATE quotations
                       SET status = ?, cost_override_reason = ?, cost_override_by = ?,
                           cost_override_at = NOW(), updated_at = NOW()
                     WHERE tenant_id = ? AND id = ?
                    """, nextStatus, note.trim(), context.userId(), tenantId, quotationId);
        } else {
            jdbc.update("UPDATE quotations SET status = ?, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                    nextStatus, tenantId, quotationId);
        }
    }

    private static boolean requiresMarginOverride(String nextStatus, QuotationResponse before) {
        return "sent".equals(nextStatus) && before.costing() != null && before.costing().requiresApproval();
    }

    @Transactional
    public NegotiationEntryResponse recordCounterOffer(UUID quotationId, CounterOfferRequest request) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        String status = quotationStatus(tenantId, quotationId);
        if (!Set.of("sent", "negotiating").contains(status)) {
            throw conflict("A customer counter-offer can only be recorded for a sent or negotiating quotation");
        }
        LatestNegotiation latest = latestNegotiation(tenantId, quotationId);
        if (latest != null && "CUSTOMER".equals(latest.partyType())) {
            throw conflict("The latest customer counter-offer must be answered before another is recorded");
        }
        int round = latest == null ? 1 : latest.round() + 1;
        validateOfferPrices(tenantId, quotationId, request.offeredUnitPrices());
        UUID entryId = insertNegotiationEntry(context, quotationId, round, "CUSTOMER", request);
        jdbc.update("UPDATE quotations SET status = 'negotiating', updated_at = NOW() " +
                        "WHERE tenant_id = ? AND id = ?",
                tenantId, quotationId);
        if ("sent".equals(status)) {
            auditService.record("QUOTATION", quotationId, "STATUS_NEGOTIATING", null,
                    toJson(readQuotation(tenantId, quotationId)));
        }
        NegotiationEntryResponse result = readNegotiationEntry(tenantId, quotationId, entryId);
        auditService.record("QUOTATION_NEGOTIATION", entryId, "CUSTOMER_COUNTEROFFER", null, toJson(result));
        return result;
    }

    @Transactional
    public NegotiationEntryResponse respondToCounterOffer(UUID quotationId, CounterOfferRequest request) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        String status = quotationStatus(tenantId, quotationId);
        LatestNegotiation latest = latestNegotiation(tenantId, quotationId);
        if (!"negotiating".equals(status) || latest == null || !"CUSTOMER".equals(latest.partyType())) {
            throw conflict("A tenant response requires an unanswered customer counter-offer");
        }
        validateOfferPrices(tenantId, quotationId, request.offeredUnitPrices());
        UUID entryId = insertNegotiationEntry(context, quotationId, latest.round(), "TENANT", request);
        NegotiationEntryResponse result = readNegotiationEntry(tenantId, quotationId, entryId);
        auditService.record("QUOTATION_NEGOTIATION", entryId, "TENANT_RESPONSE", null, toJson(result));
        return result;
    }

    @Transactional
    public QuotationResponse reviseQuotation(UUID quotationId, ReviseQuotationRequest request) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        String sourceStatus = quotationStatus(tenantId, quotationId);
        if (QUOTATION_STATUSES_NOT_REVISABLE.contains(sourceStatus)) {
            throw conflict("Quotation cannot be revised from status " + sourceStatus);
        }
        QuotationResponse original = readQuotation(tenantId, quotationId);

        String snapshot = toJson(original);
        Integer nextRevision = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision), 0) + 1 FROM quotations " +
                        "WHERE tenant_id = ? AND quotation_number = ?",
                Integer.class, tenantId, original.quotationNumber());
        jdbc.update("""
                INSERT INTO quotation_revisions
                  (tenant_id, quotation_id, revision, changed_by, change_reason, snapshot)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """,
                tenantId, quotationId, nextRevision, context.userId(), request.changeReason(), snapshot);
        jdbc.update("UPDATE quotations SET status = 'superseded', updated_at = NOW() " +
                        "WHERE tenant_id = ? AND id = ?",
                tenantId, quotationId);

        UUID revisedId = UUID.randomUUID();
        LocalDate validUntil = request.validUntil() != null ? request.validUntil() : original.validUntil();
        UUID siteId = quotationSiteId(tenantId, quotationId);
        Integer paymentTerms = request.paymentTerms() != null ? request.paymentTerms() : original.paymentTerms();
        String paymentTermsText = request.paymentTermsText() != null ? request.paymentTermsText() : original.paymentTermsText();
        String deliveryTerms = request.deliveryTerms() != null ? request.deliveryTerms() : original.deliveryTerms();
        String notes = request.notes() != null ? request.notes() : original.notes();
        BigDecimal discount = request.discountAmount() != null ? request.discountAmount() : original.discountAmount();
        jdbc.update("""
                INSERT INTO quotations
                  (id, tenant_id, quotation_number, rfq_id, customer_id, site_id, revision,
                   status, valid_until, payment_terms, payment_terms_text, delivery_terms, notes, discount_amount, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'draft', ?, COALESCE(?, 30), ?, ?, ?, COALESCE(?, 0), ?)
                """,
                revisedId, tenantId, original.quotationNumber(), original.rfqId(), original.customerId(),
                siteId, nextRevision, validUntil, paymentTerms, blank(paymentTermsText), deliveryTerms, notes, discount, context.userId());
        BigDecimal estimatedTotal = replaceQuotationLines(tenantId, revisedId, request.lines());
        jdbc.update("UPDATE quotations SET estimated_material_cost = ? WHERE tenant_id = ? AND id = ?",
                estimatedTotal, tenantId, revisedId);
        saveCostingSnapshot(tenantId, revisedId, request.lines(), discount);

        QuotationResponse result = readQuotation(tenantId, revisedId);
        auditService.record("QUOTATION", quotationId, "SUPERSEDED", snapshot, toJson(result));
        auditService.record("QUOTATION", revisedId, "REVISION_CREATE", null, toJson(result));
        return result;
    }

    private void replaceRfqLines(UUID tenantId, UUID rfqId, List<RfqLineRequest> lines) {
        int lineNo = 1;
        for (RfqLineRequest line : lines) {
            requireItemAndUom(line.itemId(), line.uomId());
            jdbc.update("""
                    INSERT INTO rfq_lines
                      (id, tenant_id, rfq_id, line_no, item_id, quantity, uom_id, specifications,
                       drawing_number, drawing_revision, material, description, target_price, notes, required_by,
                       specification_values)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """,
                    UUID.randomUUID(), tenantId, rfqId, lineNo++, line.itemId(), line.qty(),
                    line.uomId(), line.customerSpecRef(), line.drawingNumber(), line.drawingRevision(),
                    line.material(), line.description(), line.targetPrice(), line.notes(), line.requiredBy(),
                    toJson(validateSpecificationValues(line.specificationValues())));
        }
    }

    private BigDecimal replaceQuotationLines(
            UUID tenantId, UUID quotationId, List<CreateQuotationLineRequest> lines) {
        BigDecimal totalCost = BigDecimal.ZERO;
        boolean allCostsKnown = true;
        int lineNo = 1;
        UUID quotationRfqId = jdbc.query("""
                SELECT rfq_id FROM quotations WHERE tenant_id = ? AND id = ?
                """, rs -> rs.next() ? rs.getObject("rfq_id", UUID.class) : null, tenantId, quotationId);
        for (CreateQuotationLineRequest line : lines) {
            requireItemAndUom(line.itemId(), line.uomId());
            Map<String, BigDecimal> specificationValues = resolveSpecificationValues(
                    tenantId, quotationRfqId, line);
            BigDecimal costPerUnit = quotationCostingService.materialCostPerUnit(
                    tenantId, line.itemId(), line.uomId(), specificationValues);
            if (costPerUnit == null) {
                allCostsKnown = false;
            } else {
                totalCost = totalCost.add(costPerUnit.multiply(line.qty()));
            }
            Integer deliveryDays = line.deliveryWeeks() == null ? null : Math.multiplyExact(line.deliveryWeeks(), 7);
            jdbc.update("""
                    INSERT INTO quotation_lines
                      (id, tenant_id, quotation_id, rfq_line_id, line_no, item_id, quantity, uom_id,
                       unit_price, delivery_days, estimated_material_cost_per_unit,
                       gst_rate, discount_pct, drawing_number, drawing_revision, description, required_by,
                       labour_hours, machine_hours, specification_values)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, 0), ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)
                    """,
                    UUID.randomUUID(), tenantId, quotationId, line.rfqLineId(), lineNo++, line.itemId(), line.qty(),
                    line.uomId(), line.unitPrice(), deliveryDays, costPerUnit, line.gstRate(),
                    line.discountPct(), line.drawingNumber(), line.drawingRevision(), line.description(),
                    line.requiredBy(), jsonOrNull(line.labourHours() == null ? Map.of() : line.labourHours()),
                    jsonOrNull(line.machineHours() == null ? Map.of() : line.machineHours()),
                    toJson(specificationValues));
        }
        return allCostsKnown ? totalCost.setScale(2, RoundingMode.HALF_UP) : null;
    }

    private Map<String, BigDecimal> resolveSpecificationValues(
            UUID tenantId, UUID quotationRfqId, CreateQuotationLineRequest line) {
        Map<String, BigDecimal> provided = validateSpecificationValues(line.specificationValues());
        if (line.rfqLineId() == null) {
            return provided;
        }
        if (quotationRfqId == null) {
            throw badRequest("An RFQ line can only be referenced by a quotation linked to that RFQ");
        }
        List<RfqSpecificationSource> sources = jdbc.query("""
                SELECT item_id, specification_values::text AS specification_values
                  FROM rfq_lines
                 WHERE tenant_id = ? AND rfq_id = ? AND id = ?
                """, (rs, rowNum) -> new RfqSpecificationSource(
                rs.getObject("item_id", UUID.class), rs.getString("specification_values")),
                tenantId, quotationRfqId, line.rfqLineId());
        if (sources.isEmpty()) {
            throw badRequest("The referenced RFQ line does not belong to this quotation's RFQ");
        }
        RfqSpecificationSource source = sources.get(0);
        if (!source.itemId().equals(line.itemId())) {
            throw badRequest("The quotation line item must match its referenced RFQ line");
        }
        return validateSpecificationValues(readDecimalMap(source.specificationValues()));
    }

    private static Map<String, BigDecimal> validateSpecificationValues(Map<String, BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        if (values.size() > 30) {
            throw badRequest("At most 30 specification values can be supplied");
        }
        for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches("[a-z][a-z0-9_]*")
                    || entry.getValue() == null || entry.getValue().signum() < 0) {
                throw badRequest("Specification values require valid names and non-negative numbers");
            }
        }
        return Map.copyOf(values);
    }

    private void saveCostingSnapshot(UUID tenantId, UUID quotationId,
                                    List<CreateQuotationLineRequest> lines, BigDecimal discountAmount) {
        UUID quotationRfqId = jdbc.query("""
                SELECT rfq_id FROM quotations WHERE tenant_id = ? AND id = ?
                """, rs -> rs.next() ? rs.getObject("rfq_id", UUID.class) : null, tenantId, quotationId);
        List<CreateQuotationLineRequest> snapshotLines = lines.stream()
                .map(line -> new CreateQuotationLineRequest(
                        line.itemId(), line.qty(), line.uomId(), line.unitPrice(), line.deliveryWeeks(),
                        line.gstRate(), line.discountPct(), line.drawingNumber(), line.drawingRevision(),
                        line.description(), line.requiredBy(), line.labourHours(), line.machineHours(),
                        line.rfqLineId(), resolveSpecificationValues(tenantId, quotationRfqId, line)))
                .toList();
        QuotationCostingSnapshot costing = quotationCostingService.calculate(
                tenantId, snapshotLines, discountAmount);
        jdbc.update("""
                UPDATE quotations
                   SET estimated_total_cost = ?, estimated_gross_profit = ?,
                       estimated_gross_margin_pct = ?, costing_snapshot = ?::jsonb
                 WHERE tenant_id = ? AND id = ?
                """, costing.totalCost(), costing.grossProfit(), costing.grossMarginPct(),
                toJson(costing), tenantId, quotationId);
    }

    private RfqResponse readRfq(UUID tenantId, UUID rfqId) {
        List<RfqHeader> headers = jdbc.query("""
                SELECT r.id, r.rfq_number, r.customer_id, r.rfq_type_id, r.rfq_type_label,
                       r.status, r.required_by_date, r.created_at, r.customer_ref, r.notes,
                       r.delivery_terms, r.payment_terms, r.received_via, r.received_date,
                       r.technical_requirements::text AS technical_requirements,
                       COALESCE(u.display_name, u.email) AS owner_name
                  FROM rfqs r
                  LEFT JOIN app_users u ON u.id = r.created_by AND u.tenant_id = r.tenant_id
                 WHERE r.tenant_id = ? AND r.id = ?
                """, (rs, rowNum) -> new RfqHeader(
                rs.getObject("id", UUID.class),
                rs.getString("rfq_number"),
                rs.getObject("customer_id", UUID.class),
                rs.getObject("rfq_type_id", UUID.class),
                rs.getString("rfq_type_label"),
                rs.getString("status"),
                localDate(rs, "required_by_date"),
                instant(rs, "created_at"),
                rs.getString("customer_ref"),
                rs.getString("notes"),
                rs.getString("delivery_terms"),
                (Integer) rs.getObject("payment_terms"),
                rs.getString("received_via"),
                localDate(rs, "received_date"),
                rs.getString("owner_name"),
                rs.getString("technical_requirements")), tenantId, rfqId);
        if (headers.isEmpty()) {
            throw notFound("RFQ not found");
        }
        RfqHeader header = headers.get(0);
        CustomerDto customer = customerService.getCustomer(header.customerId());
        List<RfqLineData> lineData = jdbc.query("""
                SELECT id, line_no, item_id, quantity, uom_id, specifications,
                       drawing_number, drawing_revision, material, description,
                       target_price, notes, required_by, specification_values::text AS specification_values
                  FROM rfq_lines
                 WHERE tenant_id = ? AND rfq_id = ?
                 ORDER BY line_no
                """, (rs, rowNum) -> new RfqLineData(
                rs.getObject("id", UUID.class),
                rs.getInt("line_no"),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("quantity"),
                rs.getObject("uom_id", UUID.class),
                rs.getString("specifications"),
                rs.getString("drawing_number"),
                rs.getString("drawing_revision"),
                rs.getString("material"),
                rs.getString("description"),
                rs.getBigDecimal("target_price"),
                rs.getString("notes"),
                localDate(rs, "required_by"),
                readDecimalMap(rs.getString("specification_values"))), tenantId, rfqId);
        List<RfqLineResponse> lines = lineData.stream().map(line -> {
            ItemDto item = itemService.getItem(line.itemId());
            UomDto uom = uomService.getUom(line.uomId());
            return new RfqLineResponse(line.id(), line.lineNo(), line.itemId(), item.getCode(),
                    item.getName(), line.quantity(), line.uomId(), uom.getSymbol(), line.specifications(),
                    line.drawingNumber(), line.drawingRevision(), line.material(), line.description(),
                    line.targetPrice(), line.notes(), line.requiredBy(), line.specificationValues());
        }).toList();
        return new RfqResponse(header.id(), header.rfqNumber(), header.customerId(), customer.getName(),
                header.rfqTypeId(), header.rfqTypeLabel(), header.status(), header.requiredDate(),
                lines, header.createdAt(), header.customerRef(), header.notes(), header.deliveryTerms(),
                header.paymentTerms(), header.receivedVia(), header.receivedDate(), header.ownerName(),
                readMap(header.technicalRequirements()));
    }

    private QuotationResponse readQuotation(UUID tenantId, UUID quotationId) {
        List<QuotationHeader> headers = jdbc.query("""
                SELECT q.id, q.quotation_number, q.rfq_id, q.customer_id, q.revision, q.status,
                       q.valid_until, q.created_at, q.payment_terms, q.payment_terms_text, q.delivery_terms, q.notes,
                       q.discount_amount, q.decision_channel, q.decision_note, q.decided_by, q.decided_at,
                       q.costing_snapshot::text AS costing_snapshot, q.cost_override_reason,
                       q.cost_override_by, q.cost_override_at,
                       r.rfq_number, cpo.id AS cpo_id, cpo.cpo_number
                  FROM quotations q
                  LEFT JOIN rfqs r ON r.tenant_id = q.tenant_id AND r.id = q.rfq_id
                  LEFT JOIN cpos cpo ON cpo.tenant_id = q.tenant_id AND cpo.quotation_id = q.id
                 WHERE q.tenant_id = ? AND q.id = ?
                """, (rs, rowNum) -> new QuotationHeader(
                rs.getObject("id", UUID.class),
                rs.getString("quotation_number"),
                rs.getObject("rfq_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getInt("revision"),
                rs.getString("status"),
                localDate(rs, "valid_until"),
                instant(rs, "created_at"),
                (Integer) rs.getObject("payment_terms"),
                rs.getString("payment_terms_text"),
                rs.getString("delivery_terms"),
                rs.getString("notes"),
                rs.getBigDecimal("discount_amount"),
                rs.getString("decision_channel"),
                rs.getString("decision_note"),
                rs.getObject("decided_by", UUID.class),
                instant(rs, "decided_at"),
                rs.getString("costing_snapshot"),
                rs.getString("cost_override_reason"),
                rs.getObject("cost_override_by", UUID.class),
                instant(rs, "cost_override_at"),
                rs.getString("rfq_number"),
                rs.getObject("cpo_id", UUID.class),
                rs.getString("cpo_number")), tenantId, quotationId);
        if (headers.isEmpty()) {
            throw notFound("Quotation not found");
        }
        QuotationHeader header = headers.get(0);
        CustomerDto customer = customerService.getCustomer(header.customerId());
        List<QuotationLineData> lineData = jdbc.query("""
                SELECT id, rfq_line_id, item_id, quantity, uom_id, unit_price, delivery_days,
                       gst_rate, discount_pct, drawing_number, drawing_revision, description, required_by,
                       specification_values::text AS specification_values
                  FROM quotation_lines
                 WHERE tenant_id = ? AND quotation_id = ?
                 ORDER BY line_no
                """, (rs, rowNum) -> new QuotationLineData(
                rs.getObject("id", UUID.class),
                rs.getObject("rfq_line_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("quantity"),
                rs.getObject("uom_id", UUID.class),
                rs.getBigDecimal("unit_price"),
                (Integer) rs.getObject("delivery_days"),
                rs.getBigDecimal("gst_rate"),
                rs.getBigDecimal("discount_pct"),
                rs.getString("drawing_number"),
                rs.getString("drawing_revision"),
                rs.getString("description"),
                localDate(rs, "required_by"),
                readDecimalMap(rs.getString("specification_values"))), tenantId, quotationId);
        List<QuotationLineResponse> lines = lineData.stream().map(line -> {
            ItemDto item = itemService.getItem(line.itemId());
            UomDto uom = uomService.getUom(line.uomId());
            return new QuotationLineResponse(line.id(), line.itemId(), item.getCode(), item.getName(),
                    line.quantity(), line.uomId(), uom.getSymbol(), line.unitPrice(),
                    commercialLineTotal(line.quantity(), line.unitPrice(), line.discountPct()),
                    line.deliveryDays() == null ? null : line.deliveryDays() / 7,
                    line.gstRate(), line.discountPct(), line.drawingNumber(), line.drawingRevision(),
                    line.description(), line.requiredBy(), line.rfqLineId(), line.specificationValues());
        }).toList();
        List<NegotiationEntryResponse> history = readNegotiationHistory(tenantId, quotationId);
        List<RevisionLink> revisions = jdbc.query("""
                SELECT id, revision, status FROM quotations
                 WHERE tenant_id = ? AND quotation_number = ?
                 ORDER BY revision
                """, (rs, rowNum) -> new RevisionLink(
                rs.getObject("id", UUID.class), rs.getInt("revision"), rs.getString("status")),
                tenantId, header.quotationNumber());
        return new QuotationResponse(header.id(), header.quotationNumber(), header.rfqId(),
                header.customerId(), customer.getName(), header.revision(), header.status(),
                header.validUntil(), lines, history, header.createdAt(), header.paymentTerms(),
                header.deliveryTerms(), header.notes(), header.discountAmount(), header.decisionChannel(),
                header.decisionNote(), header.decidedBy(), header.decidedAt(), header.cpoId(),
                header.cpoNumber(), header.rfqNumber(), revisions, header.paymentTermsText(),
                parseCostingSnapshot(header.costingSnapshot()), header.costOverrideReason(),
                header.costOverrideBy(), header.costOverrideAt());
    }

    private List<NegotiationEntryResponse> readNegotiationHistory(UUID tenantId, UUID quotationId) {
        List<NegotiationRow> entries = jdbc.query("""
                SELECT id, round, party_type, offered_unit_prices::text AS offered_unit_prices,
                       note, created_at
                  FROM quotation_negotiation_history
                 WHERE tenant_id = ? AND quotation_id = ?
                 ORDER BY round, created_at, id
                """, (rs, rowNum) -> new NegotiationRow(
                rs.getObject("id", UUID.class),
                rs.getInt("round"),
                rs.getString("party_type"),
                rs.getString("offered_unit_prices"),
                rs.getString("note"),
                instant(rs, "created_at")), tenantId, quotationId);
        return entries.stream().map(entry -> new NegotiationEntryResponse(
                entry.id(), entry.round(), entry.partyType(), parseOfferedPrices(entry.offeredUnitPrices()),
                entry.note(), entry.createdAt())).toList();
    }

    private NegotiationEntryResponse readNegotiationEntry(UUID tenantId, UUID quotationId, UUID entryId) {
        List<NegotiationEntryResponse> entries = jdbc.query("""
                SELECT id, round, party_type, offered_unit_prices::text AS offered_unit_prices,
                       note, created_at
                  FROM quotation_negotiation_history
                 WHERE tenant_id = ? AND quotation_id = ? AND id = ?
                """, (rs, rowNum) -> new NegotiationEntryResponse(
                rs.getObject("id", UUID.class),
                rs.getInt("round"),
                rs.getString("party_type"),
                parseOfferedPrices(rs.getString("offered_unit_prices")),
                rs.getString("note"),
                instant(rs, "created_at")), tenantId, quotationId, entryId);
        if (entries.isEmpty()) {
            throw notFound("Negotiation entry not found");
        }
        return entries.get(0);
    }

    private List<OfferedUnitPrice> parseOfferedPrices(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read quotation negotiation prices", e);
        }
    }

    private UUID insertNegotiationEntry(
            BossContext context,
            UUID quotationId,
            int round,
            String partyType,
            CounterOfferRequest request) {
        UUID entryId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO quotation_negotiation_history
                  (id, tenant_id, quotation_id, round, party_type, offered_unit_prices,
                   note, material_cost_at_time, created_by)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """,
                entryId, context.tenantId(), quotationId, round, partyType,
                toJson(request.offeredUnitPrices()), request.note(),
                materialCostAtTime(context.tenantId(), quotationId), context.userId());
        return entryId;
    }

    private BigDecimal materialCostAtTime(UUID tenantId, UUID quotationId) {
        return jdbc.queryForObject("""
                SELECT CASE
                         WHEN COUNT(*) = 0 OR COUNT(estimated_material_cost_per_unit) <> COUNT(*) THEN NULL
                         ELSE SUM(quantity * estimated_material_cost_per_unit)
                       END
                  FROM quotation_lines
                 WHERE tenant_id = ? AND quotation_id = ?
                """, BigDecimal.class, tenantId, quotationId);
    }

    private void validateOfferPrices(UUID tenantId, UUID quotationId, List<OfferedUnitPrice> offers) {
        Set<UUID> seenLineIds = new java.util.HashSet<>();
        for (OfferedUnitPrice offer : offers) {
            if (!seenLineIds.add(offer.lineId())) {
                throw badRequest("Each quotation line may appear only once in an offer");
            }
            if (count("SELECT COUNT(*) FROM quotation_lines " +
                    "WHERE tenant_id = ? AND quotation_id = ? AND id = ?",
                    tenantId, quotationId, offer.lineId()) == 0) {
                throw badRequest("Offer line does not belong to this quotation");
            }
        }
    }

    private LatestNegotiation latestNegotiation(UUID tenantId, UUID quotationId) {
        List<LatestNegotiation> entries = jdbc.query("""
                SELECT round, party_type
                  FROM quotation_negotiation_history
                 WHERE tenant_id = ? AND quotation_id = ?
                 ORDER BY round DESC, created_at DESC, id DESC
                 LIMIT 1
                """, (rs, rowNum) -> new LatestNegotiation(
                rs.getInt("round"), rs.getString("party_type")), tenantId, quotationId);
        return entries.isEmpty() ? null : entries.get(0);
    }

    private String quotationStatus(UUID tenantId, UUID quotationId) {
        List<String> statuses = jdbc.query(
                "SELECT status FROM quotations WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString("status"), tenantId, quotationId);
        if (statuses.isEmpty()) {
            throw notFound("Quotation not found");
        }
        return statuses.get(0);
    }

    private String rfqStatus(UUID tenantId, UUID rfqId) {
        List<String> statuses = jdbc.query(
                "SELECT status FROM rfqs WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString("status"), tenantId, rfqId);
        if (statuses.isEmpty()) {
            throw notFound("RFQ not found");
        }
        return statuses.get(0);
    }

    private UUID quotationSiteId(UUID tenantId, UUID quotationId) {
        return jdbc.query(
                "SELECT site_id FROM quotations WHERE tenant_id = ? AND id = ?",
                rs -> rs.next() ? rs.getObject("site_id", UUID.class) : null, tenantId, quotationId);
    }

    private void requireCustomer(UUID customerId) {
        CustomerDto customer;
        try {
            customer = customerService.getCustomer(customerId);
        } catch (EntityNotFoundException e) {
            throw badRequest("Active customer not found");
        }
        if (!"active".equalsIgnoreCase(customer.getStatus())) {
            throw badRequest("Active customer not found");
        }
    }

    private void requireItemAndUom(UUID itemId, UUID uomId) {
        ItemDto item;
        UomDto uom;
        try {
            item = itemService.getItem(itemId);
            uom = uomService.getUom(uomId);
        } catch (EntityNotFoundException e) {
            throw badRequest("Active item and unit of measure must belong to this tenant");
        }
        if (!"active".equalsIgnoreCase(item.getStatus()) || uom.getId() == null) {
            throw badRequest("Active item and unit of measure must belong to this tenant");
        }
    }

    private long count(String sql, Object... args) {
        Long result = jdbc.queryForObject(sql, Long.class, args);
        return result == null ? 0 : result;
    }

    private UUID requireActiveSite(BossContext context) {
        if (context.siteId() != null) {
            return context.siteId();
        }
        List<UUID> sites = jdbc.query(
                "SELECT id FROM physical_sites WHERE tenant_id = ? AND status = 'active' ORDER BY created_at, id",
                (rs, rowNum) -> rs.getObject("id", UUID.class), context.tenantId());
        if (sites.size() == 1) {
            return sites.get(0);
        }
        if (sites.isEmpty()) {
            throw badRequest("Add an active site before creating a sales document");
        }
        throw badRequest("Select a site before creating a sales document");
    }

    private BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null || context.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant user context is required");
        }
        return context;
    }

    private String nextDocumentNumber(String prefix) {
        return prefix + "-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw badRequest("Page must be non-negative and size must be between 1 and 100");
        }
    }

    private QuotationPdf.Party seller(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT le.legal_name, le.pan, le.registered_address::text AS address,
                       (SELECT gstin FROM gst_registrations
                         WHERE tenant_id = le.tenant_id AND status = 'active'
                         ORDER BY created_at LIMIT 1) AS gstin,
                       (SELECT contact_email FROM company_profiles
                         WHERE tenant_id = le.tenant_id ORDER BY created_at LIMIT 1) AS email,
                       (SELECT contact_phone FROM company_profiles
                         WHERE tenant_id = le.tenant_id ORDER BY created_at LIMIT 1) AS phone
                  FROM legal_entities le
                 WHERE le.tenant_id = ?
                 ORDER BY le.created_at
                 LIMIT 1
                """, tenantId);
        if (rows.isEmpty()) return new QuotationPdf.Party("", "", "", "", "", "", "", "", "");
        Map<String, Object> row = rows.get(0);
        String[] address = addressParts(stringValue(row.get("address")));
        return new QuotationPdf.Party(stringValue(row.get("legal_name")), address[0], address[1], address[2],
                stringValue(row.get("gstin")), stringValue(row.get("pan")),
                stringValue(row.get("email")), stringValue(row.get("phone")), "");
    }

    private QuotationPdf.Party buyer(UUID tenantId, UUID customerId) {
        if (customerId == null) return new QuotationPdf.Party("", "", "", "", "", "", "", "", "");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.legal_name, c.name, c.gstin, c.pan, c.billing_address::text AS address,
                       ct.name AS contact_name, ct.email, ct.phone
                  FROM customers c
                  LEFT JOIN LATERAL (
                    SELECT name, email, phone FROM customer_contacts
                     WHERE tenant_id = c.tenant_id AND customer_id = c.id
                     ORDER BY is_primary DESC, created_at LIMIT 1
                  ) ct ON true
                 WHERE c.tenant_id = ? AND c.id = ?
                """, tenantId, customerId);
        if (rows.isEmpty()) return new QuotationPdf.Party("", "", "", "", "", "", "", "", "");
        Map<String, Object> row = rows.get(0);
        String[] address = addressParts(stringValue(row.get("address")));
        String name = stringValue(row.get("legal_name"));
        if (name.isBlank()) name = stringValue(row.get("name"));
        return new QuotationPdf.Party(name, address[0], address[1], address[2],
                stringValue(row.get("gstin")), stringValue(row.get("pan")),
                stringValue(row.get("email")), stringValue(row.get("phone")), stringValue(row.get("contact_name")));
    }

    private String[] addressParts(String json) {
        if (json == null || json.isBlank()) return new String[] {"", "", ""};
        try {
            JsonNode node = objectMapper.readTree(json);
            return new String[] {node.path("line1").asText(""), node.path("city").asText(""), node.path("state").asText("")};
        } catch (JsonProcessingException exception) {
            return new String[] {json, "", ""};
        }
    }

    private List<FieldChange> fieldChanges(String oldJson, String newJson) {
        JsonNode previous = readTree(oldJson);
        JsonNode next = readTree(newJson);
        if (!isObject(previous) && !isObject(next)) return List.of();
        Map<String, JsonNode> fields = historyFields(previous, next);
        List<FieldChange> changes = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : fields.entrySet()) {
            if (changes.size() == 12) break;
            addFieldChange(changes, field.getKey(), field.getValue(),
                    next == null ? null : next.get(field.getKey()));
        }
        return changes;
    }

    private static boolean isObject(JsonNode node) {
        return node != null && node.isObject();
    }

    private static Map<String, JsonNode> historyFields(JsonNode previous, JsonNode next) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        if (isObject(previous)) previous.fieldNames().forEachRemaining(key -> fields.put(key, previous.get(key)));
        if (isObject(next)) next.fieldNames().forEachRemaining(key -> fields.putIfAbsent(key, next.get(key)));
        return fields;
    }

    private static void addFieldChange(List<FieldChange> changes, String key, JsonNode before, JsonNode after) {
        if (skipHistoryField(key)) return;
        if (isContainer(before) || isContainer(after)) {
            addArrayChange(changes, key, before, after);
            return;
        }
        addScalarChange(changes, key, before, after);
    }

    private static boolean isContainer(JsonNode node) {
        return node != null && node.isContainerNode();
    }

    private static void addArrayChange(List<FieldChange> changes, String key, JsonNode before, JsonNode after) {
        if (before == null || !before.isArray()) return;
        int beforeCount = before.size();
        int afterCount = after != null && after.isArray() ? after.size() : 0;
        if (beforeCount != afterCount) {
            changes.add(new FieldChange(label(key), beforeCount + " rows", afterCount + " rows"));
        }
    }

    private static void addScalarChange(List<FieldChange> changes, String key, JsonNode before, JsonNode after) {
        String beforeValue = scalar(before);
        String afterValue = scalar(after);
        if (!beforeValue.equals(afterValue)) changes.add(new FieldChange(label(key), beforeValue, afterValue));
    }

    private static boolean skipHistoryField(String key) {
        String lower = key.toLowerCase(Locale.ENGLISH);
        return lower.equals("id") || lower.endsWith("id") || lower.endsWith("by")
                || lower.equals("createdat") || lower.equals("updatedat");
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static String scalar(JsonNode node) {
        if (node == null || node.isNull()) return "—";
        String value = node.asText();
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String label(String key) {
        String spaced = key.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
        return spaced.substring(0, 1).toUpperCase(Locale.ENGLISH) + spaced.substring(1);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String jsonOrNull(Object value) {
        return value == null ? null : toJson(value);
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private Map<String, BigDecimal> readDecimalMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not read specification values", exception);
        }
    }

    private QuotationCostingSnapshot parseCostingSnapshot(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, QuotationCostingSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not read quotation costing snapshot", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize Sales audit snapshot", e);
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    static BigDecimal commercialLineTotal(BigDecimal qty, BigDecimal unitPrice, BigDecimal discountPct) {
        BigDecimal discount = discountPct == null ? BigDecimal.ZERO : discountPct.movePointLeft(2);
        return qty.multiply(unitPrice).multiply(BigDecimal.ONE.subtract(discount))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private record RfqHeader(UUID id, String rfqNumber, UUID customerId, UUID rfqTypeId,
                             String rfqTypeLabel, String status, LocalDate requiredDate, Instant createdAt,
                             String customerRef, String notes, String deliveryTerms, Integer paymentTerms,
                             String receivedVia, LocalDate receivedDate, String ownerName, String technicalRequirements) {}

    private record RfqLineData(UUID id, int lineNo, UUID itemId, BigDecimal quantity, UUID uomId,
                               String specifications, String drawingNumber, String drawingRevision,
                               String material, String description, BigDecimal targetPrice, String notes,
                               LocalDate requiredBy, Map<String, BigDecimal> specificationValues) {}

    private record RfqSpecificationSource(UUID itemId, String specificationValues) {}

    private record QuotationHeader(UUID id, String quotationNumber, UUID rfqId, UUID customerId,
                                   int revision, String status, LocalDate validUntil, Instant createdAt,
                                   Integer paymentTerms, String paymentTermsText, String deliveryTerms, String notes,
                                   BigDecimal discountAmount, String decisionChannel, String decisionNote,
                                   UUID decidedBy, Instant decidedAt, String costingSnapshot, String costOverrideReason,
                                   UUID costOverrideBy, Instant costOverrideAt, String rfqNumber, UUID cpoId,
                                   String cpoNumber) {}

    private record QuotationLineData(UUID id, UUID rfqLineId, UUID itemId, BigDecimal quantity, UUID uomId,
                                    BigDecimal unitPrice, Integer deliveryDays, BigDecimal gstRate,
                                    BigDecimal discountPct, String drawingNumber, String drawingRevision,
                                    String description, LocalDate requiredBy,
                                    Map<String, BigDecimal> specificationValues) {}

    private record NegotiationRow(UUID id, int round, String partyType, String offeredUnitPrices,
                                  String note, Instant createdAt) {}

    private record LatestNegotiation(int round, String partyType) {}
}
