package in.boss.sales.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.masterdata.api.dto.BOMExplosionMaterialDto;
import in.boss.masterdata.api.dto.CustomerDto;
import in.boss.masterdata.api.dto.ItemDto;
import in.boss.masterdata.api.dto.UomDto;
import in.boss.masterdata.domain.BomExplosionService;
import in.boss.masterdata.domain.CustomerService;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.ItemService;
import in.boss.masterdata.domain.UomService;
import in.boss.sales.api.SalesApiDtos.CreateQuotationLineRequest;
import in.boss.sales.api.SalesApiDtos.CreateQuotationRequest;
import in.boss.sales.api.SalesApiDtos.CreateRfqRequest;
import in.boss.sales.api.SalesApiDtos.CounterOfferRequest;
import in.boss.sales.api.SalesApiDtos.NegotiationEntryResponse;
import in.boss.sales.api.SalesApiDtos.OfferedUnitPrice;
import in.boss.sales.api.SalesApiDtos.PageResponse;
import in.boss.sales.api.SalesApiDtos.QuotationLineResponse;
import in.boss.sales.api.SalesApiDtos.QuotationResponse;
import in.boss.sales.api.SalesApiDtos.ReviseQuotationRequest;
import in.boss.sales.api.SalesApiDtos.RfqLineRequest;
import in.boss.sales.api.SalesApiDtos.RfqLineResponse;
import in.boss.sales.api.SalesApiDtos.RfqResponse;
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
import java.util.List;
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
    private final BomExplosionService bomExplosionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public SalesService(JdbcTemplate jdbc,
                        CustomerService customerService,
                        ItemService itemService,
                        UomService uomService,
                        BomExplosionService bomExplosionService,
                        AuditService auditService,
                        ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.customerService = customerService;
        this.itemService = itemService;
        this.uomService = uomService;
        this.bomExplosionService = bomExplosionService;
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
        jdbc.update("""
                INSERT INTO rfqs
                  (id, tenant_id, rfq_number, customer_id, site_id, status, received_date,
                   required_by_date, rfq_type_id, rfq_type_label, created_by)
                VALUES (?, ?, ?, ?, ?, 'draft', CURRENT_DATE, ?, ?, ?, ?)
                """,
                rfqId, tenantId, rfqNumber, request.customerId(), siteId, request.requiredDate(),
                request.rfqTypeId(), request.rfqTypeLabel(), context.userId());
        replaceRfqLines(tenantId, rfqId, request.lines());

        RfqResponse result = readRfq(tenantId, rfqId);
        auditService.record("RFQ", rfqId, "CREATE", null, toJson(result));
        return result;
    }

    @Transactional(readOnly = true)
    public PageResponse<RfqResponse> listRfqs(UUID customerId, String status, int page, int size) {
        UUID tenantId = requireContext().tenantId();
        validatePage(page, size);
        if (status != null && !RFQ_STATUSES.contains(status)) {
            throw badRequest("Unsupported RFQ status");
        }

        StringBuilder where = new StringBuilder(" WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (customerId != null) {
            where.append(" AND customer_id = ?");
            args.add(customerId);
        }
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM rfqs" + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<UUID> ids = jdbc.query(
                "SELECT id FROM rfqs" + where + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
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
        jdbc.update("""
                UPDATE rfqs
                   SET required_by_date = ?, rfq_type_id = ?, rfq_type_label = ?, updated_at = NOW()
                 WHERE tenant_id = ? AND id = ?
                """,
                request.requiredDate(), request.rfqTypeId(), request.rfqTypeLabel(), tenantId, rfqId);
        jdbc.update("DELETE FROM rfq_lines WHERE tenant_id = ? AND rfq_id = ?", tenantId, rfqId);
        replaceRfqLines(tenantId, rfqId, request.lines());

        RfqResponse result = readRfq(tenantId, rfqId);
        auditService.record("RFQ", rfqId, "UPDATE", null, toJson(result));
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
        jdbc.update("UPDATE rfqs SET status = ?, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                nextStatus, tenantId, rfqId);
        RfqResponse result = readRfq(tenantId, rfqId);
        auditService.record("RFQ", rfqId, "STATUS_" + nextStatus.toUpperCase(), null, toJson(result));
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
        }

        UUID quotationId = UUID.randomUUID();
        String quotationNumber = nextDocumentNumber("QT");
        jdbc.update("""
                INSERT INTO quotations
                  (id, tenant_id, quotation_number, rfq_id, customer_id, site_id, revision,
                   status, valid_until, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 1, 'draft', ?, ?)
                """,
                quotationId, tenantId, quotationNumber, request.rfqId(), request.customerId(),
                siteId, request.validUntil(), context.userId());
        BigDecimal estimatedTotal = replaceQuotationLines(tenantId, quotationId, request.lines());
        jdbc.update("UPDATE quotations SET estimated_material_cost = ? WHERE tenant_id = ? AND id = ?",
                estimatedTotal, tenantId, quotationId);

        QuotationResponse result = readQuotation(tenantId, quotationId);
        auditService.record("QUOTATION", quotationId, "CREATE", null, toJson(result));
        return result;
    }

    @Transactional(readOnly = true)
    public QuotationResponse getQuotation(UUID quotationId) {
        return readQuotation(requireContext().tenantId(), quotationId);
    }

    @Transactional
    public QuotationResponse updateQuotationStatus(UUID quotationId, String nextStatus) {
        UUID tenantId = requireContext().tenantId();
        String current = quotationStatus(tenantId, quotationId);
        boolean validTransition = ("draft".equals(current) && "sent".equals(nextStatus))
                || (Set.of("sent", "negotiating").contains(current)
                && Set.of("rejected", "expired").contains(nextStatus));
        if (!validTransition) {
            throw conflict("Invalid quotation status transition: " + current + " -> " + nextStatus);
        }
        jdbc.update("UPDATE quotations SET status = ?, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                nextStatus, tenantId, quotationId);
        QuotationResponse result = readQuotation(tenantId, quotationId);
        auditService.record("QUOTATION", quotationId, "STATUS_" + nextStatus.toUpperCase(), null, toJson(result));
        return result;
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
        jdbc.update("""
                INSERT INTO quotations
                  (id, tenant_id, quotation_number, rfq_id, customer_id, site_id, revision,
                   status, valid_until, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'draft', ?, ?)
                """,
                revisedId, tenantId, original.quotationNumber(), original.rfqId(), original.customerId(),
                siteId, nextRevision, validUntil, context.userId());
        BigDecimal estimatedTotal = replaceQuotationLines(tenantId, revisedId, request.lines());
        jdbc.update("UPDATE quotations SET estimated_material_cost = ? WHERE tenant_id = ? AND id = ?",
                estimatedTotal, tenantId, revisedId);

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
                      (id, tenant_id, rfq_id, line_no, item_id, quantity, uom_id, specifications)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), tenantId, rfqId, lineNo++, line.itemId(), line.qty(),
                    line.uomId(), line.customerSpecRef());
        }
    }

    private BigDecimal replaceQuotationLines(
            UUID tenantId, UUID quotationId, List<CreateQuotationLineRequest> lines) {
        BigDecimal totalCost = BigDecimal.ZERO;
        boolean allCostsKnown = true;
        int lineNo = 1;
        for (CreateQuotationLineRequest line : lines) {
            requireItemAndUom(line.itemId(), line.uomId());
            BigDecimal costPerUnit = estimateMaterialCostPerUnit(line.itemId(), line.uomId());
            if (costPerUnit == null) {
                allCostsKnown = false;
            } else {
                totalCost = totalCost.add(costPerUnit.multiply(line.qty()));
            }
            Integer deliveryDays = line.deliveryWeeks() == null ? null : Math.multiplyExact(line.deliveryWeeks(), 7);
            jdbc.update("""
                    INSERT INTO quotation_lines
                      (id, tenant_id, quotation_id, line_no, item_id, quantity, uom_id,
                       unit_price, delivery_days, estimated_material_cost_per_unit)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), tenantId, quotationId, lineNo++, line.itemId(), line.qty(),
                    line.uomId(), line.unitPrice(), deliveryDays, costPerUnit);
        }
        return allCostsKnown ? totalCost.setScale(2, RoundingMode.HALF_UP) : null;
    }

    private BigDecimal estimateMaterialCostPerUnit(UUID finishedItemId, UUID quoteUomId) {
        ItemDto finishedItem = itemService.getItem(finishedItemId);
        String quoteUomSymbol = uomSymbol(quoteUomId);
        if (finishedItem.getUomSymbol() == null || !finishedItem.getUomSymbol().equals(quoteUomSymbol)) {
            return null;
        }

        List<BOMExplosionMaterialDto> materials =
                bomExplosionService.explodeBOM(finishedItemId, BigDecimal.ONE).getFlatMaterials();
        if (materials == null || materials.isEmpty()) {
            return null;
        }

        BigDecimal total = BigDecimal.ZERO;
        for (BOMExplosionMaterialDto material : materials) {
            ItemDto materialItem = itemService.getItem(material.getItemId());
            if (materialItem.getStandardCost() == null
                    || materialItem.getUomSymbol() == null
                    || !materialItem.getUomSymbol().equals(material.getUomSymbol())) {
                return null;
            }
            total = total.add(material.getQty().multiply(materialItem.getStandardCost()));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private RfqResponse readRfq(UUID tenantId, UUID rfqId) {
        List<RfqHeader> headers = jdbc.query("""
                SELECT id, rfq_number, customer_id, rfq_type_id, rfq_type_label,
                       status, required_by_date, created_at
                  FROM rfqs
                 WHERE tenant_id = ? AND id = ?
                """, (rs, rowNum) -> new RfqHeader(
                rs.getObject("id", UUID.class),
                rs.getString("rfq_number"),
                rs.getObject("customer_id", UUID.class),
                rs.getObject("rfq_type_id", UUID.class),
                rs.getString("rfq_type_label"),
                rs.getString("status"),
                localDate(rs, "required_by_date"),
                instant(rs, "created_at")), tenantId, rfqId);
        if (headers.isEmpty()) {
            throw notFound("RFQ not found");
        }
        RfqHeader header = headers.get(0);
        CustomerDto customer = customerService.getCustomer(header.customerId());
        List<RfqLineData> lineData = jdbc.query("""
                SELECT id, line_no, item_id, quantity, uom_id, specifications
                  FROM rfq_lines
                 WHERE tenant_id = ? AND rfq_id = ?
                 ORDER BY line_no
                """, (rs, rowNum) -> new RfqLineData(
                rs.getObject("id", UUID.class),
                rs.getInt("line_no"),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("quantity"),
                rs.getObject("uom_id", UUID.class),
                rs.getString("specifications")), tenantId, rfqId);
        List<RfqLineResponse> lines = lineData.stream().map(line -> {
            ItemDto item = itemService.getItem(line.itemId());
            UomDto uom = uomService.getUom(line.uomId());
            return new RfqLineResponse(line.id(), line.lineNo(), line.itemId(), item.getCode(),
                    item.getName(), line.quantity(), line.uomId(), uom.getSymbol(), line.specifications());
        }).toList();
        return new RfqResponse(header.id(), header.rfqNumber(), header.customerId(), customer.getName(),
                header.rfqTypeId(), header.rfqTypeLabel(), header.status(), header.requiredDate(),
                lines, header.createdAt());
    }

    private QuotationResponse readQuotation(UUID tenantId, UUID quotationId) {
        List<QuotationHeader> headers = jdbc.query("""
                SELECT id, quotation_number, rfq_id, customer_id, revision, status,
                       valid_until, created_at
                  FROM quotations
                 WHERE tenant_id = ? AND id = ?
                """, (rs, rowNum) -> new QuotationHeader(
                rs.getObject("id", UUID.class),
                rs.getString("quotation_number"),
                rs.getObject("rfq_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getInt("revision"),
                rs.getString("status"),
                localDate(rs, "valid_until"),
                instant(rs, "created_at")), tenantId, quotationId);
        if (headers.isEmpty()) {
            throw notFound("Quotation not found");
        }
        QuotationHeader header = headers.get(0);
        CustomerDto customer = customerService.getCustomer(header.customerId());
        List<QuotationLineData> lineData = jdbc.query("""
                SELECT id, item_id, quantity, uom_id, unit_price, delivery_days
                  FROM quotation_lines
                 WHERE tenant_id = ? AND quotation_id = ?
                 ORDER BY line_no
                """, (rs, rowNum) -> new QuotationLineData(
                rs.getObject("id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("quantity"),
                rs.getObject("uom_id", UUID.class),
                rs.getBigDecimal("unit_price"),
                (Integer) rs.getObject("delivery_days")), tenantId, quotationId);
        List<QuotationLineResponse> lines = lineData.stream().map(line -> {
            ItemDto item = itemService.getItem(line.itemId());
            UomDto uom = uomService.getUom(line.uomId());
            return new QuotationLineResponse(line.id(), line.itemId(), item.getCode(), item.getName(),
                    line.quantity(), line.uomId(), uom.getSymbol(), line.unitPrice(),
                    line.quantity().multiply(line.unitPrice()).setScale(2, RoundingMode.HALF_UP),
                    line.deliveryDays() == null ? null : line.deliveryDays() / 7);
        }).toList();
        List<NegotiationEntryResponse> history = readNegotiationHistory(tenantId, quotationId);
        return new QuotationResponse(header.id(), header.quotationNumber(), header.rfqId(),
                header.customerId(), customer.getName(), header.revision(), header.status(),
                header.validUntil(), lines, history, header.createdAt());
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

    private String uomSymbol(UUID uomId) {
        return uomService.getUom(uomId).getSymbol();
    }

    private long count(String sql, Object... args) {
        Long result = jdbc.queryForObject(sql, Long.class, args);
        return result == null ? 0 : result;
    }

    private UUID requireActiveSite(BossContext context) {
        UUID siteId = context.siteId();
        if (siteId == null) {
            throw badRequest("A site must be selected to create a sales document");
        }
        return siteId;
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize Sales audit snapshot", e);
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

    private record RfqHeader(UUID id, String rfqNumber, UUID customerId, UUID rfqTypeId,
                             String rfqTypeLabel, String status, LocalDate requiredDate, Instant createdAt) {}

    private record RfqLineData(UUID id, int lineNo, UUID itemId, BigDecimal quantity,
                               UUID uomId, String specifications) {}

    private record QuotationHeader(UUID id, String quotationNumber, UUID rfqId, UUID customerId,
                                   int revision, String status, LocalDate validUntil,
                                   Instant createdAt) {}

    private record QuotationLineData(UUID id, UUID itemId, BigDecimal quantity, UUID uomId,
                                    BigDecimal unitPrice, Integer deliveryDays) {}

    private record NegotiationRow(UUID id, int round, String partyType, String offeredUnitPrices,
                                  String note, Instant createdAt) {}

    private record LatestNegotiation(int round, String partyType) {}
}
