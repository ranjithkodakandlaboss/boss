package in.boss.procurement.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.masterdata.api.dto.ItemDto;
import in.boss.masterdata.api.dto.UomDto;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.ItemService;
import in.boss.masterdata.domain.UomService;
import in.boss.procurement.api.ProcurementApiDtos.CreatePrLineRequest;
import in.boss.procurement.api.ProcurementApiDtos.CreatePrRequest;
import in.boss.procurement.api.ProcurementApiDtos.PageResponse;
import in.boss.procurement.api.ProcurementApiDtos.PrLineResponse;
import in.boss.procurement.api.ProcurementApiDtos.PurchaseRequisitionResponse;
import in.boss.procurement.api.ProcurementApiDtos.RejectPrRequest;
import in.boss.procurement.api.ProcurementApiDtos.UpdatePrRequest;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@PreAuthorize(BossPermissions.PR_ACCESS)
public class PurchaseRequisitionService {

    private static final Set<String> VALID_ORIGIN_TYPES = Set.of("manual", "production_job", "stock_driven");
    private static final Set<String> VALID_PRIORITIES = Set.of("low", "normal", "high", "urgent");

    private final JdbcTemplate jdbc;
    private final ItemService itemService;
    private final UomService uomService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public PurchaseRequisitionService(JdbcTemplate jdbc,
                                      ItemService itemService,
                                      UomService uomService,
                                      AuditService auditService,
                                      ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.itemService = itemService;
        this.uomService = uomService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PurchaseRequisitionResponse createPr(CreatePrRequest request) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        UUID siteId = request.siteId() != null ? request.siteId() : requireActiveSite(context);

        String originType = request.originType() == null ? "manual" : request.originType().trim().toLowerCase(Locale.ROOT);
        if (!VALID_ORIGIN_TYPES.contains(originType)) {
            throw badRequest("Invalid originType: " + request.originType() + ". Must be one of: " + VALID_ORIGIN_TYPES);
        }

        String priority = request.priority() == null ? "normal" : request.priority().trim().toLowerCase(Locale.ROOT);
        if (!VALID_PRIORITIES.contains(priority)) {
            throw badRequest("Invalid priority: " + request.priority() + ". Must be one of: " + VALID_PRIORITIES);
        }

        List<CreatePrLineRequest> lines = request.lines() != null ? new ArrayList<>(request.lines()) : new ArrayList<>();
        UUID jobId = request.jobId();
        UUID replenishmentRequestId = request.replenishmentRequestId();

        if ("production_job".equals(originType)) {
            if (jobId == null) {
                throw badRequest("jobId is required when originType is production_job");
            }
            requireProductionJob(tenantId, jobId);
            if (lines.isEmpty()) {
                lines = loadShortageLinesFromJob(tenantId, jobId);
                if (lines.isEmpty()) {
                    throw badRequest("No material shortages found for production job " + jobId);
                }
            }
        } else if ("stock_driven".equals(originType)) {
            if (replenishmentRequestId != null) {
                ReplenishmentInfo repInfo = requirePendingReplenishmentRequest(tenantId, replenishmentRequestId);
                if (lines.isEmpty()) {
                    ItemDto item = itemService.getItem(repInfo.itemId());
                    lines.add(new CreatePrLineRequest(
                            repInfo.itemId(),
                            repInfo.requestedQty(),
                            repInfo.requestedQty(),
                            item.getUomId(),
                            null,
                            repInfo.storeId(),
                            request.requiredDate(),
                            "Stock-driven replenishment from request " + replenishmentRequestId,
                            null
                    ));
                }
            }
        }

        if (lines.isEmpty()) {
            throw badRequest("Purchase requisition must contain at least one line item");
        }

        // Validate line items and UOMs
        for (CreatePrLineRequest line : lines) {
            BigDecimal qty = line.effectiveQty();
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                throw badRequest("Quantity must be greater than zero for all lines");
            }
            requireActiveItemAndUom(line.itemId(), line.uomId());
        }

        UUID prId = UUID.randomUUID();
        String prNumber = nextDocumentNumber("PR");
        UUID referenceId = jobId != null ? jobId : replenishmentRequestId;

        jdbc.update("""
                INSERT INTO purchase_requisitions
                  (id, tenant_id, pr_number, site_id, status, priority, required_by_date,
                   origin_type, reference_type, reference_id, job_id, pr_type_id, pr_type_label,
                   notes, requested_by)
                VALUES (?, ?, ?, ?, 'draft', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                prId, tenantId, prNumber, siteId, priority, request.requiredDate(),
                originType, originType, referenceId, jobId, request.prTypeId(), request.prTypeLabel(),
                request.notes(), context.userId());

        insertPrLines(tenantId, prId, lines);

        if ("stock_driven".equals(originType) && replenishmentRequestId != null) {
            jdbc.update("""
                    UPDATE replenishment_requests
                    SET status = 'pr_created', pr_id = ?
                    WHERE tenant_id = ? AND id = ?
                    """,
                    prId, tenantId, replenishmentRequestId);
        }

        PurchaseRequisitionResponse response = getPr(prId);
        auditService.record("purchase_requisitions", prId, "CREATED", null, toJson(response));
        return response;
    }

    @Transactional
    public PurchaseRequisitionResponse getPr(UUID prId) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();

        PrHeader header = fetchPrHeader(tenantId, prId);
        // Sync procurement coverage if PR is approved, partially_ordered, or ordered
        if (Set.of("approved", "partially_ordered", "ordered").contains(header.status())) {
            header = recalculateCoverageInternal(tenantId, prId, header);
        }

        List<PrLineResponse> lines = fetchPrLines(tenantId, prId);
        return mapToResponse(header, lines);
    }

    @Transactional(readOnly = true)
    public PageResponse<PurchaseRequisitionResponse> listPrs(String status, String originType, int page, int size) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        validatePage(page, size);

        StringBuilder sql = new StringBuilder("""
                SELECT id, pr_number, site_id, origin_type, job_id, status, priority,
                       pr_type_id, pr_type_label, required_by_date, notes,
                       requested_by, approved_by, approved_at, rejection_reason,
                       rejected_by, rejected_at, created_at, updated_at
                FROM purchase_requisitions
                WHERE tenant_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (status != null && !status.isBlank()) {
            sql.append(" AND LOWER(status) = ?");
            params.add(status.trim().toLowerCase(Locale.ROOT));
        }

        if (originType != null && !originType.isBlank()) {
            sql.append(" AND LOWER(origin_type) = ?");
            params.add(originType.trim().toLowerCase(Locale.ROOT));
        }

        String countSql = "SELECT COUNT(*) FROM (" + sql + ") AS pr_count";
        Long total = jdbc.queryForObject(countSql, Long.class, params.toArray());
        long totalElements = total != null ? total : 0;

        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(size);
        params.add(page * size);

        List<PrHeader> headers = jdbc.query(sql.toString(), (rs, rowNum) -> mapHeader(rs), params.toArray());
        List<PurchaseRequisitionResponse> content = headers.stream()
                .map(h -> {
                    List<PrLineResponse> lines = fetchPrLines(tenantId, h.id());
                    return mapToResponse(h, lines);
                })
                .toList();

        return new PageResponse<>(content, page, size, totalElements);
    }

    @Transactional
    public PurchaseRequisitionResponse updateDraftPr(UUID prId, UpdatePrRequest request) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();

        PrHeader existing = fetchPrHeader(tenantId, prId);
        if (!"draft".equals(existing.status())) {
            throw conflict("Only draft purchase requisitions can be edited. Current status is " + existing.status());
        }

        UUID siteId = request.siteId() != null ? request.siteId() : existing.siteId();
        String priority = request.priority() != null ? request.priority().trim().toLowerCase(Locale.ROOT) : existing.priority();
        if (request.priority() != null && !VALID_PRIORITIES.contains(priority)) {
            throw badRequest("Invalid priority: " + request.priority() + ". Must be one of: " + VALID_PRIORITIES);
        }

        LocalDate reqDate = request.requiredDate() != null ? request.requiredDate() : existing.requiredDate();
        UUID prTypeId = request.prTypeId() != null ? request.prTypeId() : existing.prTypeId();
        String prTypeLabel = request.prTypeLabel() != null ? request.prTypeLabel() : existing.prTypeLabel();
        String notes = request.notes() != null ? request.notes() : existing.notes();

        jdbc.update("""
                UPDATE purchase_requisitions
                SET site_id = ?, priority = ?, required_by_date = ?, pr_type_id = ?,
                    pr_type_label = ?, notes = ?, updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """,
                siteId, priority, reqDate, prTypeId, prTypeLabel, notes, tenantId, prId);

        if (request.lines() != null && !request.lines().isEmpty()) {
            for (CreatePrLineRequest line : request.lines()) {
                BigDecimal qty = line.effectiveQty();
                if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                    throw badRequest("Quantity must be greater than zero for all lines");
                }
                requireActiveItemAndUom(line.itemId(), line.uomId());
            }

            jdbc.update("DELETE FROM pr_lines WHERE tenant_id = ? AND pr_id = ?", tenantId, prId);
            insertPrLines(tenantId, prId, request.lines());
        }

        PurchaseRequisitionResponse updated = getPr(prId);
        auditService.record("purchase_requisitions", prId, "UPDATED", toJson(existing), toJson(updated));
        return updated;
    }

    @Transactional
    public PurchaseRequisitionResponse submitPr(UUID prId) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();

        PrHeader existing = fetchPrHeader(tenantId, prId);
        if (!"draft".equals(existing.status())) {
            throw conflict("Only draft purchase requisitions can be submitted. Current status is " + existing.status());
        }

        List<PrLineResponse> lines = fetchPrLines(tenantId, prId);
        if (lines.isEmpty()) {
            throw badRequest("Purchase requisition must contain at least one line item before submission");
        }

        jdbc.update("""
                UPDATE purchase_requisitions
                SET status = 'submitted', updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """, tenantId, prId);

        PurchaseRequisitionResponse updated = getPr(prId);
        auditService.record("purchase_requisitions", prId, "SUBMITTED", toJson(existing), toJson(updated));
        return updated;
    }

    @Transactional
    @PreAuthorize(BossPermissions.PR_APPROVE)
    public PurchaseRequisitionResponse approvePr(UUID prId) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();

        PrHeader existing = fetchPrHeader(tenantId, prId);
        if (!"submitted".equals(existing.status())) {
            throw conflict("Only submitted purchase requisitions can be approved. Current status is " + existing.status());
        }

        jdbc.update("""
                UPDATE purchase_requisitions
                SET status = 'approved', approved_by = ?, approved_at = NOW(), updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """, context.userId(), tenantId, prId);

        PurchaseRequisitionResponse updated = getPr(prId);
        auditService.record("purchase_requisitions", prId, "APPROVED", toJson(existing), toJson(updated));
        return updated;
    }

    @Transactional
    @PreAuthorize(BossPermissions.PR_APPROVE)
    public PurchaseRequisitionResponse rejectPr(UUID prId, RejectPrRequest request) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();

        if (request == null || request.reason() == null || request.reason().trim().isEmpty()) {
            throw badRequest("Rejection reason is required");
        }

        PrHeader existing = fetchPrHeader(tenantId, prId);
        if (!"submitted".equals(existing.status())) {
            throw conflict("Only submitted purchase requisitions can be rejected. Current status is " + existing.status());
        }

        String reason = request.reason().trim();
        jdbc.update("""
                UPDATE purchase_requisitions
                SET status = 'rejected', rejection_reason = ?, rejected_by = ?,
                    rejected_at = NOW(), updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """, reason, context.userId(), tenantId, prId);

        PurchaseRequisitionResponse updated = getPr(prId);
        auditService.record("purchase_requisitions", prId, "REJECTED", toJson(existing), toJson(updated));
        return updated;
    }

    @Transactional
    public PurchaseRequisitionResponse cancelPr(UUID prId) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();

        PrHeader existing = fetchPrHeader(tenantId, prId);
        if ("cancelled".equals(existing.status()) || "rejected".equals(existing.status())) {
            throw conflict("Cannot cancel purchase requisition in status " + existing.status());
        }

        // Rule 14: Cancellation must respect existing supplier commitments.
        // Check if active issued SPO quantities exist linked to this PR
        Long activeSpoCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM spo_lines sl
                JOIN spos s ON s.id = sl.spo_id
                WHERE sl.pr_line_id IN (SELECT id FROM pr_lines WHERE pr_id = ? AND tenant_id = ?)
                  AND s.tenant_id = ?
                  AND s.status NOT IN ('draft', 'cancelled')
                  AND sl.quantity > 0
                """, Long.class, prId, tenantId, tenantId);

        if (activeSpoCount != null && activeSpoCount > 0) {
            throw conflict("Cannot cancel Purchase Requisition because active supplier commitments exist");
        }

        jdbc.update("""
                UPDATE purchase_requisitions
                SET status = 'cancelled', updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """, tenantId, prId);

        PurchaseRequisitionResponse updated = getPr(prId);
        auditService.record("purchase_requisitions", prId, "CANCELLED", toJson(existing), toJson(updated));
        return updated;
    }

    @Transactional
    public PurchaseRequisitionResponse recalculateCoverage(UUID prId) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        PrHeader header = fetchPrHeader(tenantId, prId);
        recalculateCoverageInternal(tenantId, prId, header);
        return getPr(prId);
    }

    private PrHeader recalculateCoverageInternal(UUID tenantId, UUID prId, PrHeader currentHeader) {
        // Procurement coverage transitions apply to approved, partially_ordered, and ordered PRs
        if (!Set.of("approved", "partially_ordered", "ordered").contains(currentHeader.status())) {
            return currentHeader;
        }

        List<PrLineRecord> lines = jdbc.query("""
                SELECT id, line_no, quantity
                FROM pr_lines
                WHERE tenant_id = ? AND pr_id = ?
                ORDER BY line_no ASC
                """,
                (rs, rowNum) -> new PrLineRecord(
                        rs.getObject("id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getBigDecimal("quantity")
                ), tenantId, prId);

        if (lines.isEmpty()) {
            return currentHeader;
        }

        boolean anyOrdered = false;
        boolean allFullyCovered = true;

        for (PrLineRecord line : lines) {
            // Cancelled orders retain coverage only for quantities already received.
            BigDecimal activeOrderedQty = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(
                      CASE WHEN s.status = 'cancelled' THEN sl.received_qty ELSE sl.quantity END
                    ), 0)
                    FROM spo_lines sl
                    JOIN spos s ON s.id = sl.spo_id AND s.tenant_id = sl.tenant_id
                    WHERE sl.tenant_id = ? AND sl.pr_line_id = ?
                      AND s.tenant_id = ?
                      AND s.status <> 'draft'
                    """, BigDecimal.class, tenantId, line.id(), tenantId);

            if (activeOrderedQty == null) {
                activeOrderedQty = BigDecimal.ZERO;
            }

            jdbc.update("""
                    UPDATE pr_lines
                    SET ordered_qty = ?
                    WHERE tenant_id = ? AND id = ?
                    """, activeOrderedQty, tenantId, line.id());

            if (activeOrderedQty.compareTo(BigDecimal.ZERO) > 0) {
                anyOrdered = true;
            }

            // Rule 13: Enforce approved quantity limits
            if (activeOrderedQty.compareTo(line.quantity()) < 0) {
                allFullyCovered = false;
            }
        }

        String targetStatus;
        if (allFullyCovered) {
            targetStatus = "ordered";
        } else if (anyOrdered) {
            targetStatus = "partially_ordered";
        } else {
            targetStatus = "approved";
        }

        if (!targetStatus.equals(currentHeader.status())) {
            jdbc.update("""
                    UPDATE purchase_requisitions
                    SET status = ?, updated_at = NOW()
                    WHERE tenant_id = ? AND id = ?
                    """, targetStatus, tenantId, prId);
            auditService.record("purchase_requisitions", prId, "COVERAGE_RECALCULATED",
                    toJson(java.util.Map.of("status", currentHeader.status())),
                    toJson(java.util.Map.of("status", targetStatus)));
            return fetchPrHeader(tenantId, prId);
        }

        return currentHeader;
    }

    private void insertPrLines(UUID tenantId, UUID prId, List<CreatePrLineRequest> lines) {
        int lineNo = 1;
        for (CreatePrLineRequest line : lines) {
            UUID lineId = UUID.randomUUID();
            BigDecimal qty = line.effectiveQty();
            String note = line.effectiveNotes();

            jdbc.update("""
                    INSERT INTO pr_lines
                      (id, tenant_id, pr_id, line_no, item_id, quantity, uom_id,
                       est_unit_price, store_id, notes, required_date, ordered_qty)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """,
                    lineId, tenantId, prId, lineNo++, line.itemId(), qty, line.uomId(),
                    line.estUnitPrice(), line.storeId(), note, line.requiredDate());
        }
    }

    private List<CreatePrLineRequest> loadShortageLinesFromJob(UUID tenantId, UUID jobId) {
        return jdbc.query("""
                SELECT item_id, uom_id, (required_qty - issued_qty + returned_qty) AS shortage_qty
                FROM job_material_requirements
                WHERE tenant_id = ? AND job_id = ? AND (required_qty - issued_qty + returned_qty) > 0
                """,
                (rs, rowNum) -> new CreatePrLineRequest(
                        rs.getObject("item_id", UUID.class),
                        rs.getBigDecimal("shortage_qty"),
                        rs.getBigDecimal("shortage_qty"),
                        rs.getObject("uom_id", UUID.class),
                        null,
                        null,
                        null,
                        "Production job shortage for job " + jobId,
                        null
                ), tenantId, jobId);
    }

    private PrHeader fetchPrHeader(UUID tenantId, UUID prId) {
        List<PrHeader> headers = jdbc.query("""
                SELECT id, pr_number, site_id, origin_type, job_id, status, priority,
                       pr_type_id, pr_type_label, required_by_date, notes,
                       requested_by, approved_by, approved_at, rejection_reason,
                       rejected_by, rejected_at, created_at, updated_at
                FROM purchase_requisitions
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, rowNum) -> mapHeader(rs), tenantId, prId);

        if (headers.isEmpty()) {
            throw notFound("Purchase requisition not found: " + prId);
        }
        return headers.get(0);
    }

    private List<PrLineResponse> fetchPrLines(UUID tenantId, UUID prId) {
        return jdbc.query("""
                SELECT l.id, l.line_no, l.item_id, i.code AS item_code, i.name AS item_name,
                       l.quantity, l.ordered_qty, l.uom_id, u.symbol AS uom_symbol,
                       l.est_unit_price, l.store_id, l.required_date, l.notes
                FROM pr_lines l
                JOIN items i ON i.id = l.item_id
                JOIN units_of_measure u ON u.id = l.uom_id
                WHERE l.tenant_id = ? AND l.pr_id = ?
                ORDER BY l.line_no ASC
                """,
                (rs, rowNum) -> new PrLineResponse(
                        rs.getObject("id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getInt("line_no"),
                        rs.getObject("item_id", UUID.class),
                        rs.getString("item_code"),
                        rs.getString("item_name"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("ordered_qty"),
                        rs.getObject("uom_id", UUID.class),
                        rs.getString("uom_symbol"),
                        rs.getBigDecimal("est_unit_price"),
                        rs.getObject("store_id", UUID.class),
                        localDate(rs, "required_date"),
                        rs.getString("notes"),
                        rs.getString("notes")
                ), tenantId, prId);
    }

    private PurchaseRequisitionResponse mapToResponse(PrHeader header, List<PrLineResponse> lines) {
        BigDecimal totalQty = lines.stream()
                .map(PrLineResponse::qty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalOrderedQty = lines.stream()
                .map(l -> l.orderedQty() != null ? l.orderedQty() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new PurchaseRequisitionResponse(
                header.id(),
                header.prNumber(),
                header.siteId(),
                header.originType(),
                header.jobId(),
                header.status(),
                header.priority(),
                header.prTypeId(),
                header.prTypeLabel(),
                header.requiredDate(),
                header.notes(),
                header.requestedBy(),
                header.approvedBy(),
                header.approvedAt(),
                header.rejectionReason(),
                header.rejectedBy(),
                header.rejectedAt(),
                lines,
                totalQty,
                totalOrderedQty,
                header.createdAt(),
                header.updatedAt()
        );
    }

    private PrHeader mapHeader(ResultSet rs) throws SQLException {
        return new PrHeader(
                rs.getObject("id", UUID.class),
                rs.getString("pr_number"),
                rs.getObject("site_id", UUID.class),
                rs.getString("origin_type"),
                rs.getObject("job_id", UUID.class),
                rs.getString("status"),
                rs.getString("priority"),
                rs.getObject("pr_type_id", UUID.class),
                rs.getString("pr_type_label"),
                localDate(rs, "required_by_date"),
                rs.getString("notes"),
                rs.getObject("requested_by", UUID.class),
                rs.getObject("approved_by", UUID.class),
                instant(rs, "approved_at"),
                rs.getString("rejection_reason"),
                rs.getObject("rejected_by", UUID.class),
                instant(rs, "rejected_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private void requireActiveItemAndUom(UUID itemId, UUID uomId) {
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

    private void requireProductionJob(UUID tenantId, UUID jobId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM production_jobs WHERE tenant_id = ? AND id = ?",
                Long.class, tenantId, jobId);
        if (count == null || count == 0) {
            throw badRequest("Active production job not found for this tenant: " + jobId);
        }
    }

    private ReplenishmentInfo requirePendingReplenishmentRequest(UUID tenantId, UUID requestId) {
        List<ReplenishmentInfo> results = jdbc.query("""
                SELECT item_id, store_id, requested_qty
                FROM replenishment_requests
                WHERE tenant_id = ? AND id = ? AND status = 'pending'
                """,
                (rs, rowNum) -> new ReplenishmentInfo(
                        rs.getObject("item_id", UUID.class),
                        rs.getObject("store_id", UUID.class),
                        rs.getBigDecimal("requested_qty")
                ), tenantId, requestId);

        if (results.isEmpty()) {
            throw badRequest("Pending replenishment request not found for this tenant: " + requestId);
        }
        return results.get(0);
    }

    private UUID requireActiveSite(BossContext context) {
        UUID siteId = context.siteId();
        if (siteId == null) {
            throw badRequest("A site must be selected to create a purchase requisition");
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
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
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
            throw new IllegalStateException("Could not serialize PR audit snapshot", e);
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

    private record PrHeader(
            UUID id,
            String prNumber,
            UUID siteId,
            String originType,
            UUID jobId,
            String status,
            String priority,
            UUID prTypeId,
            String prTypeLabel,
            LocalDate requiredDate,
            String notes,
            UUID requestedBy,
            UUID approvedBy,
            Instant approvedAt,
            String rejectionReason,
            UUID rejectedBy,
            Instant rejectedAt,
            Instant createdAt,
            Instant updatedAt) {}

    private record PrLineRecord(UUID id, int lineNo, BigDecimal quantity) {}

    private record ReplenishmentInfo(UUID itemId, UUID storeId, BigDecimal requestedQty) {}
}
