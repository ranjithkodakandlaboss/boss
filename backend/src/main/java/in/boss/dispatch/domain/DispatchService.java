package in.boss.dispatch.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.dispatch.api.DispatchDtos.*;
import in.boss.quality.domain.QualityService;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import in.boss.shared.outbox.OutboxPublisher;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.*;

@Service
public class DispatchService {

    private final JdbcTemplate jdbc;
    private final QualityService qualityService;
    private final AuditService auditService;
    private final OutboxPublisher outbox;
    private final ObjectMapper objectMapper;

    public DispatchService(
            JdbcTemplate jdbc,
            QualityService qualityService,
            AuditService auditService,
            OutboxPublisher outbox,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.qualityService = qualityService;
        this.auditService = auditService;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    private BossContext requireContext() {
        BossContext ctx = BossContextHolder.get();
        if (ctx == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "BossContext required");
        }
        return ctx;
    }

    // ==========================================
    // DISPATCH CREATION & LISTING
    // ==========================================

    @Transactional
    public DispatchDetail createDispatch(CreateDispatchRequest req) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        if (req.customerId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer ID is required");
        }
        if (req.dispatchDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dispatch date is required");
        }

        // Validate customer belongs to tenant
        validateCustomer(tenantId, req.customerId());
        validateDispatchMethodOption(tenantId, req.dispatchMethodOptionId());

        // Validate site if provided, or fallback to default site
        UUID siteId = resolveSiteId(tenantId, req.sourceSiteId());

        // Quality Gate Check if Job is linked
        UUID jobId = req.jobId();
        UUID clearanceCertId = checkQualityGate(tenantId, jobId);

        // Generate Dispatch Number (DSP-YYYY-XXX)
        String dispatchNumber = generateDispatchNumber(tenantId);

        // Generate Challan Number
        int currentYear = Year.now().getValue();
        String challanNumber = dispatchNumber.replace("DSP-", "CHL-");

        UUID dispatchId = UUID.randomUUID();
        String initialStatus = "pending_docs";

        BigDecimal totalQty = calculateTotalQty(req.lines());
        int lineCount = req.lines() != null ? req.lines().size() : 0;

        // Insert into dispatches table
        jdbc.update("""
                INSERT INTO dispatches (
                  id, tenant_id, dispatch_number, cpo_id, customer_id, site_id,
                  status, dispatch_date, expected_delivery, vehicle_number, transporter, carrier,
                  driver_name, lr_number, source_store_id, source_store_name, job_id,
                  quality_release_id, challan_number, notes, total_qty, line_count, dispatch_method_option_id,
                  created_by, created_at, updated_at
                ) VALUES (
                  ?, ?, ?, ?, ?, ?,
                  ?, ?, ?, ?, ?, ?,
                  ?, ?, ?, ?, ?,
                  ?, ?, ?, ?, ?, ?,
                  ?, NOW(), NOW()
                )
                """,
                dispatchId, tenantId, dispatchNumber, req.cpoId(), req.customerId(), siteId,
                initialStatus, req.dispatchDate(), req.expectedDelivery(), req.vehicleNumber(),
                req.carrier(), req.carrier(), req.driverName(), req.lrNumber(), req.sourceStoreId(),
                req.sourceStoreName(), jobId, clearanceCertId, challanNumber, req.notes(),
                totalQty, lineCount, req.dispatchMethodOptionId(), ctx.userId()
        );

        // Insert dispatch lines if provided
        insertLines(tenantId, dispatchId, jobId, clearanceCertId, req.lines());

        // Insert packages if provided
        insertPackages(tenantId, dispatchId, req.packages());

        // Record Audit event
        auditService.record("dispatch", dispatchId, "create", null,
                toJson(Map.of("dispatchNumber", dispatchNumber, "status", initialStatus, "customerId", req.customerId().toString())));

        // Outbox event
        outbox.publish("dispatch", dispatchId, "dispatch.created",
                toJson(Map.of("dispatchNumber", dispatchNumber, "status", initialStatus, "tenantId", tenantId)));

        return getDispatch(dispatchId);
    }

    public List<DispatchSummary> listDispatches(String siteFilter, String statusFilter, String search) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        StringBuilder sql = new StringBuilder("""
                SELECT d.id, d.dispatch_number, d.customer_id, c.name AS customer_name,
                       d.cpo_id, cp.cpo_number, d.job_id, pj.job_number,
                       d.site_id, ps.name AS site_name, d.source_store_id, st.name AS store_name,
                       COALESCE(d.carrier, d.transporter) AS carrier,
                       d.dispatch_method_option_id, d.dispatch_method_label_snapshot AS dispatch_method_label,
                       d.vehicle_number, d.driver_name,
                       d.lr_number, d.dispatch_date, d.expected_delivery, d.total_qty, d.line_count,
                       d.status, d.has_pod, d.created_at
                FROM dispatches d
                JOIN customers c ON c.id = d.customer_id AND c.tenant_id = d.tenant_id
                JOIN physical_sites ps ON ps.id = d.site_id AND ps.tenant_id = d.tenant_id
                LEFT JOIN cpos cp ON cp.id = d.cpo_id AND cp.tenant_id = d.tenant_id
                LEFT JOIN production_jobs pj ON pj.id = d.job_id AND pj.tenant_id = d.tenant_id
                LEFT JOIN stores st ON st.id = d.source_store_id AND st.tenant_id = d.tenant_id
                WHERE d.tenant_id = ?
                """);

        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (siteFilter != null && !siteFilter.isBlank() && !"all".equalsIgnoreCase(siteFilter)) {
            try {
                params.add(UUID.fromString(siteFilter));
                sql.append(" AND d.site_id = ?");
            } catch (IllegalArgumentException ignored) {}
        }

        if (statusFilter != null && !statusFilter.isBlank() && !"all".equalsIgnoreCase(statusFilter)) {
            if ("ready".equalsIgnoreCase(statusFilter)) {
                sql.append(" AND d.status IN ('draft', 'pending', 'pending_docs')");
            } else {
                params.add(statusFilter.toLowerCase());
                sql.append(" AND d.status = ?");
            }
        }

        if (search != null && !search.isBlank()) {
            params.add("%" + search.trim().toLowerCase() + "%");
            params.add("%" + search.trim().toLowerCase() + "%");
            sql.append(" AND (LOWER(d.dispatch_number) LIKE ? OR LOWER(c.name) LIKE ?)");
        }

        sql.append(" ORDER BY d.created_at DESC");

        return jdbc.query(sql.toString(), (rs, i) -> {
            UUID jobId = getUuid(rs, "job_id");
            String jobNumber = rs.getString("job_number");
            List<String> jobs = jobNumber != null ? List.of(jobNumber) : Collections.emptyList();

            return new DispatchSummary(
                    (UUID) rs.getObject("id"),
                    rs.getString("dispatch_number"),
                    (UUID) rs.getObject("customer_id"),
                    rs.getString("customer_name"),
                    getUuid(rs, "cpo_id"),
                    rs.getString("cpo_number"),
                    jobId,
                    jobNumber,
                    jobs,
                    (UUID) rs.getObject("site_id"),
                    rs.getString("site_name"),
                    getUuid(rs, "source_store_id"),
                    rs.getString("store_name"),
                    rs.getString("carrier"),
                    getUuid(rs, "dispatch_method_option_id"),
                    rs.getString("dispatch_method_label"),
                    rs.getString("vehicle_number"),
                    rs.getString("driver_name"),
                    rs.getString("lr_number"),
                    getLocalDate(rs, "dispatch_date"),
                    getLocalDate(rs, "expected_delivery"),
                    rs.getBigDecimal("total_qty"),
                    rs.getInt("line_count"),
                    rs.getString("status"),
                    rs.getBoolean("has_pod"),
                    rs.getTimestamp("created_at").toInstant()
            );
        }, params.toArray());
    }

    public DispatchDetail getDispatch(UUID dispatchId) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        List<DispatchDetail> list = jdbc.query("""
                SELECT d.id, d.dispatch_number, d.customer_id, c.name AS customer_name,
                       d.cpo_id, cp.cpo_number, d.job_id, pj.job_number,
                       d.site_id, ps.name AS site_name, d.source_store_id,
                       COALESCE(d.source_store_name, st.name) AS store_name,
                       COALESCE(d.carrier, d.transporter) AS carrier,
                       d.dispatch_method_option_id, d.dispatch_method_label_snapshot AS dispatch_method_label,
                       d.vehicle_number, d.driver_name,
                       d.lr_number, d.dispatch_date, d.expected_delivery, d.actual_delivery_date,
                       d.delivery_condition, d.pod_reference, d.delivery_notes,
                       d.total_qty, d.line_count, d.status, d.has_pod,
                       d.quality_release_id, d.challan_number, d.challan_gcs_path,
                       d.created_at, d.updated_at
                FROM dispatches d
                JOIN customers c ON c.id = d.customer_id AND c.tenant_id = d.tenant_id
                JOIN physical_sites ps ON ps.id = d.site_id AND ps.tenant_id = d.tenant_id
                LEFT JOIN cpos cp ON cp.id = d.cpo_id AND cp.tenant_id = d.tenant_id
                LEFT JOIN production_jobs pj ON pj.id = d.job_id AND pj.tenant_id = d.tenant_id
                LEFT JOIN stores st ON st.id = d.source_store_id AND st.tenant_id = d.tenant_id
                WHERE d.tenant_id = ? AND d.id = ?
                """, (rs, i) -> {
            UUID id = (UUID) rs.getObject("id");
            UUID jobId = getUuid(rs, "job_id");
            String jobNumber = rs.getString("job_number");
            List<String> jobs = jobNumber != null ? List.of(jobNumber) : Collections.emptyList();

            return new DispatchDetail(
                    id,
                    rs.getString("dispatch_number"),
                    (UUID) rs.getObject("customer_id"),
                    rs.getString("customer_name"),
                    getUuid(rs, "cpo_id"),
                    rs.getString("cpo_number"),
                    jobId,
                    jobNumber,
                    jobs,
                    (UUID) rs.getObject("site_id"),
                    rs.getString("site_name"),
                    getUuid(rs, "source_store_id"),
                    rs.getString("store_name"),
                    null, // destination
                    rs.getString("carrier"),
                    getUuid(rs, "dispatch_method_option_id"),
                    rs.getString("dispatch_method_label"),
                    rs.getString("vehicle_number"),
                    rs.getString("driver_name"),
                    rs.getString("lr_number"),
                    getLocalDate(rs, "dispatch_date"),
                    getLocalDate(rs, "expected_delivery"),
                    getLocalDate(rs, "actual_delivery_date"),
                    rs.getString("delivery_condition"),
                    rs.getString("pod_reference"),
                    rs.getString("delivery_notes"),
                    null, // receivedByName (will load from pod_records if present)
                    null, // receivedAt
                    rs.getBoolean("has_pod"),
                    rs.getString("status"),
                    rs.getBigDecimal("total_qty"),
                    rs.getInt("line_count"),
                    getUuid(rs, "quality_release_id"),
                    null, // qualityReleaseStatus
                    null, // qualityReleaseBy
                    null, // qualityInspectionId
                    null, // qualityInspectionNumber
                    null, // qualityInspectionResult
                    null, // failedInspectionId
                    null, // failedInspectionNumber
                    null, // linkedNcrId
                    null, // linkedNcrNumber
                    rs.getString("challan_number"),
                    rs.getString("challan_gcs_path"),
                    Collections.emptyList(), // lines
                    Collections.emptyList(), // packages
                    Collections.emptyList(), // history
                    Collections.emptyList(), // linkedInvoices
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
            );
        }, tenantId, dispatchId);

        if (list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dispatch not found");
        }

        DispatchDetail base = list.get(0);

        // Load POD Record if exists
        String receivedByName = null;
        Instant receivedAt = null;
        try {
            var podMap = jdbc.queryForMap(
                    "SELECT received_by, received_at FROM pod_records WHERE tenant_id = ? AND dispatch_id = ?",
                    tenantId, dispatchId);
            receivedByName = (String) podMap.get("received_by");
            var ts = (java.sql.Timestamp) podMap.get("received_at");
            if (ts != null) receivedAt = ts.toInstant();
        } catch (EmptyResultDataAccessException ignored) {}

        // Load Dispatch Lines
        List<DispatchLineDto> lines = jdbc.query("""
                SELECT dl.id, dl.dispatch_id, dl.cpo_line_id, dl.job_id, pj.job_number,
                       dl.item_id, it.code AS item_code, it.name AS item_name,
                       dl.lot_id, ml.lot_number, dl.quantity, dl.uom_id, uom.symbol AS uom_symbol,
                       dl.packing_spec_id, dl.clearance_cert_id, dl.line_no
                FROM dispatch_lines dl
                JOIN items it ON it.id = dl.item_id AND it.tenant_id = dl.tenant_id
                JOIN units_of_measure uom ON uom.id = dl.uom_id
                LEFT JOIN production_jobs pj ON pj.id = dl.job_id AND pj.tenant_id = dl.tenant_id
                LEFT JOIN material_lots ml ON ml.id = dl.lot_id AND ml.tenant_id = dl.tenant_id
                WHERE dl.tenant_id = ? AND dl.dispatch_id = ?
                ORDER BY dl.line_no ASC
                """, (rs, i) -> new DispatchLineDto(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("dispatch_id"),
                getUuid(rs, "cpo_line_id"),
                getUuid(rs, "job_id"),
                rs.getString("job_number"),
                (UUID) rs.getObject("item_id"),
                rs.getString("item_code"),
                rs.getString("item_name"),
                getUuid(rs, "lot_id"),
                rs.getString("lot_number"),
                rs.getBigDecimal("quantity"),
                (UUID) rs.getObject("uom_id"),
                rs.getString("uom_symbol"),
                getUuid(rs, "packing_spec_id"),
                getUuid(rs, "clearance_cert_id"),
                rs.getInt("line_no")
        ), tenantId, dispatchId);

        // Load Packages
        List<DispatchPackageDto> packages = jdbc.query("""
                SELECT id, dispatch_id, package_code, package_type, product_description,
                       quantity, uom, weight_kg, length_mm, width_mm, height_mm, packing_notes, created_at
                FROM dispatch_packages
                WHERE tenant_id = ? AND dispatch_id = ?
                ORDER BY created_at ASC
                """, (rs, i) -> new DispatchPackageDto(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("dispatch_id"),
                rs.getString("package_code"),
                rs.getString("package_type"),
                rs.getString("product_description"),
                rs.getBigDecimal("quantity"),
                rs.getString("uom"),
                rs.getBigDecimal("weight_kg"),
                rs.getBigDecimal("length_mm"),
                rs.getBigDecimal("width_mm"),
                rs.getBigDecimal("height_mm"),
                rs.getString("packing_notes"),
                rs.getTimestamp("created_at").toInstant()
        ), tenantId, dispatchId);

        // Check Quality Info for the Job
        UUID qualityReleaseId = base.qualityReleaseId();
        String qualityReleaseStatus = null;
        String qualityReleaseBy = null;
        UUID qualityInspectionId = null;
        String qualityInspectionNumber = null;
        String qualityInspectionResult = null;
        UUID failedInspectionId = null;
        String failedInspectionNumber = null;
        UUID linkedNcrId = null;
        String linkedNcrNumber = null;

        if (base.jobId() != null) {
            // Find latest inspection for this job
            try {
                var insp = jdbc.queryForMap("""
                        SELECT qi.id, qi.inspection_number, qi.result, u.display_name AS inspector_name
                        FROM quality_inspections qi
                        LEFT JOIN app_users u ON u.id = qi.inspector_id
                        WHERE qi.tenant_id = ? AND qi.reference_id = ?
                        ORDER BY qi.created_at DESC LIMIT 1
                        """, tenantId, base.jobId());
                qualityInspectionId = (UUID) insp.get("id");
                qualityInspectionNumber = (String) insp.get("inspection_number");
                qualityInspectionResult = (String) insp.get("result");

                if ("passed".equals(qualityInspectionResult)) {
                    qualityReleaseStatus = "released";
                    qualityReleaseBy = (String) insp.get("inspector_name");
                } else if ("fail".equals(qualityInspectionResult)) {
                    failedInspectionId = qualityInspectionId;
                    failedInspectionNumber = qualityInspectionNumber;
                }
            } catch (EmptyResultDataAccessException ignored) {}

            // Find linked NCR if any
            try {
                var ncr = jdbc.queryForMap("""
                        SELECT n.id, n.ncr_number
                        FROM ncrs n
                        JOIN quality_inspections qi ON qi.id = n.inspection_id
                        WHERE n.tenant_id = ? AND qi.reference_id = ?
                        ORDER BY n.created_at DESC LIMIT 1
                        """, tenantId, base.jobId());
                linkedNcrId = (UUID) ncr.get("id");
                linkedNcrNumber = (String) ncr.get("ncr_number");
            } catch (EmptyResultDataAccessException ignored) {}
        }

        // Load Linked Invoices
        List<LinkedInvoiceDto> linkedInvoices = jdbc.query("""
                SELECT id, invoice_number, status, total_amount, invoice_date
                FROM invoices
                WHERE tenant_id = ? AND dispatch_id = ?
                ORDER BY created_at DESC
                """, (rs, i) -> new LinkedInvoiceDto(
                (UUID) rs.getObject("id"),
                rs.getString("invoice_number"),
                rs.getString("status"),
                rs.getBigDecimal("total_amount"),
                getLocalDate(rs, "invoice_date")
        ), tenantId, dispatchId);

        // Load History from audit_events
        List<DispatchHistoryDto> history = jdbc.query("""
                SELECT a.id, a.action, u.display_name AS actor_name, a.created_at
                FROM audit_events a
                LEFT JOIN app_users u ON u.id = a.actor_id
                WHERE a.tenant_id = ? AND a.entity_type = 'dispatch' AND a.entity_id = ?
                ORDER BY a.created_at DESC
                """, (rs, i) -> {
            String act = rs.getString("action");
            String title = switch (act) {
                case "create" -> "Dispatch Created";
                case "status_change" -> "Status Updated";
                case "pod_recorded" -> "Proof of Delivery Confirmed";
                case "packing_recorded" -> "Packing Recorded";
                default -> "Dispatch Updated (" + act + ")";
            };
            return new DispatchHistoryDto(
                    (UUID) rs.getObject("id"),
                    act,
                    title,
                    "Action recorded by " + (rs.getString("actor_name") != null ? rs.getString("actor_name") : "system"),
                    rs.getString("actor_name"),
                    rs.getTimestamp("created_at").toInstant()
            );
        }, tenantId, dispatchId);

        return new DispatchDetail(
                base.id(),
                base.dispatchNumber(),
                base.customerId(),
                base.customerName(),
                base.cpoId(),
                base.cpoNumber(),
                base.jobId(),
                base.jobNumber(),
                base.jobs(),
                base.sourceSiteId(),
                base.sourceSiteName(),
                base.sourceStoreId(),
                base.sourceStoreName(),
                base.destination(),
                base.carrier(),
                base.dispatchMethodOptionId(),
                base.dispatchMethodLabel(),
                base.vehicleNumber(),
                base.driverName(),
                base.lrNumber(),
                base.dispatchDate(),
                base.expectedDelivery(),
                base.actualDeliveryDate(),
                base.deliveryCondition(),
                base.podReference(),
                base.deliveryNotes(),
                receivedByName,
                receivedAt,
                base.hasPoD(),
                base.status(),
                base.totalQty(),
                lines.size(),
                qualityReleaseId,
                qualityReleaseStatus,
                qualityReleaseBy,
                qualityInspectionId,
                qualityInspectionNumber,
                qualityInspectionResult,
                failedInspectionId,
                failedInspectionNumber,
                linkedNcrId,
                linkedNcrNumber,
                base.challanNumber(),
                base.challanGcsPath(),
                lines,
                packages,
                history,
                linkedInvoices,
                base.createdAt(),
                base.updatedAt()
        );
    }

    // ==========================================
    // STATUS TRANSITIONS & LIFECYCLE
    // ==========================================

    @Transactional
    public DispatchDetail updateStatus(UUID dispatchId, UpdateStatusRequest req) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();
        String nextStatus = req.status() != null ? req.status().toLowerCase().trim() : "";

        var current = getDispatch(dispatchId);
        String oldStatus = current.status();

        if (oldStatus.equals(nextStatus)) {
            return current;
        }

        // Validate state transitions
        boolean valid = switch (oldStatus) {
            case "draft" -> List.of("pending_docs", "dispatched", "cancelled").contains(nextStatus);
            case "pending", "pending_docs" -> List.of("dispatched", "cancelled").contains(nextStatus);
            case "packed" -> List.of("dispatched", "cancelled").contains(nextStatus);
            case "dispatched" -> List.of("in_transit", "delivered", "delayed").contains(nextStatus);
            case "in_transit" -> List.of("delivered", "delayed").contains(nextStatus);
            case "delayed" -> List.of("in_transit", "delivered").contains(nextStatus);
            default -> false;
        };

        if (!valid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid status transition from " + oldStatus + " to " + nextStatus);
        }

        // If transitioning to dispatched, check Quality eligibility if job exists
        if ("dispatched".equals(nextStatus) && current.jobId() != null) {
            var eligibility = qualityService.computeEligibility(tenantId, current.jobId());
            if (!eligibility.eligible()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cannot dispatch: Quality inspection gate active. " + eligibility.reason());
            }
        }

        // Update dispatches
        if ("dispatched".equals(nextStatus)) {
            jdbc.update("""
                    UPDATE dispatches
                    SET status = ?, dispatched_at = NOW(), updated_at = NOW()
                    WHERE tenant_id = ? AND id = ?
                    """, nextStatus, tenantId, dispatchId);

            // Update CPO lines delivered_qty
            for (var line : current.lines()) {
                if (line.cpoLineId() != null) {
                    jdbc.update("""
                            UPDATE cpo_lines
                            SET delivered_qty = delivered_qty + ?
                            WHERE tenant_id = ? AND id = ?
                            """, line.quantity(), tenantId, line.cpoLineId());
                }
            }
        } else if ("delivered".equals(nextStatus)) {
            jdbc.update("""
                    UPDATE dispatches
                    SET status = ?, actual_delivery_date = COALESCE(actual_delivery_date, CURRENT_DATE), updated_at = NOW()
                    WHERE tenant_id = ? AND id = ?
                    """, nextStatus, tenantId, dispatchId);
        } else {
            jdbc.update("""
                    UPDATE dispatches
                    SET status = ?, updated_at = NOW()
                    WHERE tenant_id = ? AND id = ?
                    """, nextStatus, tenantId, dispatchId);
        }

        // Record Audit and Outbox
        auditService.record("dispatch", dispatchId, "status_change",
                toJson(Map.of("oldStatus", oldStatus)),
                toJson(Map.of("newStatus", nextStatus, "notes", req.notes() != null ? req.notes() : "")));

        outbox.publish("dispatch", dispatchId, "dispatch.status_changed",
                toJson(Map.of("oldStatus", oldStatus, "newStatus", nextStatus, "dispatchNumber", current.dispatchNumber())));

        return getDispatch(dispatchId);
    }

    // ==========================================
    // PROOF OF DELIVERY (PoD)
    // ==========================================

    @Transactional
    public DispatchDetail recordPoD(UUID dispatchId, RecordPodRequest req) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        if (req.receivedBy() == null || req.receivedBy().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Received by is mandatory for proof of delivery.");
        }

        var current = getDispatch(dispatchId);

        // Idempotency: if PoD already recorded with same receiver, return existing record safely
        if (current.hasPoD()) {
            if (req.receivedBy().trim().equalsIgnoreCase(current.receivedByName())) {
                return current;
            }
        }

        LocalDate deliveryDate = req.deliveredDate() != null ? req.deliveredDate() : LocalDate.now();
        String condition = req.condition() != null ? req.condition().toLowerCase().trim() : "good";
        String podRef = req.podReference() != null && !req.podReference().isBlank()
                ? req.podReference().trim()
                : "POD-" + current.dispatchNumber();

        // Update dispatches record
        jdbc.update("""
                UPDATE dispatches
                SET status = 'delivered', has_pod = TRUE, actual_delivery_date = ?,
                    delivery_condition = ?, pod_reference = ?, delivery_notes = ?, updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """,
                deliveryDate, condition, podRef, req.notes(), tenantId, dispatchId);

        // Upsert into pod_records
        jdbc.update("""
                INSERT INTO pod_records (
                  id, tenant_id, dispatch_id, received_by, received_at,
                  signature_ref, notes, delivered_date, condition, pod_reference, created_at
                ) VALUES (
                  gen_random_uuid(), ?, ?, ?, NOW(),
                  ?, ?, ?, ?, ?, NOW()
                )
                ON CONFLICT (dispatch_id) DO UPDATE SET
                  received_by = EXCLUDED.received_by,
                  received_at = EXCLUDED.received_at,
                  delivered_date = EXCLUDED.delivered_date,
                  condition = EXCLUDED.condition,
                  pod_reference = EXCLUDED.pod_reference,
                  notes = EXCLUDED.notes
                """,
                tenantId, dispatchId, req.receivedBy().trim(), req.signatureRef(),
                req.notes(), deliveryDate, condition, podRef);

        auditService.record("dispatch", dispatchId, "pod_recorded", null,
                toJson(Map.of("receivedBy", req.receivedBy(), "condition", condition, "podReference", podRef)));

        // Publish event for Finance integration to trigger customer invoice
        outbox.publish("dispatch", dispatchId, "dispatch.delivered",
                toJson(Map.of("dispatchId", dispatchId.toString(), "dispatchNumber", current.dispatchNumber(),
                        "cpoId", current.cpoId() != null ? current.cpoId().toString() : "",
                        "customerId", current.customerId().toString(),
                        "deliveredAt", deliveryDate.toString(), "receivedBy", req.receivedBy().trim())));

        return getDispatch(dispatchId);
    }

    // ==========================================
    // DELETE DISPATCH
    // ==========================================

    @Transactional
    public void deleteDispatch(UUID dispatchId) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        var dispatch = getDispatch(dispatchId);

        if ("delivered".equalsIgnoreCase(dispatch.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot delete a delivered dispatch.");
        }

        // Check if invoice is linked
        Integer invoiceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoices WHERE tenant_id = ? AND dispatch_id = ?",
                Integer.class, tenantId, dispatchId);
        if (invoiceCount != null && invoiceCount > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot delete dispatch linked to an invoice.");
        }

        // Delete child rows
        jdbc.update("DELETE FROM dispatch_packages WHERE tenant_id = ? AND dispatch_id = ?", tenantId, dispatchId);
        jdbc.update("DELETE FROM pod_records WHERE tenant_id = ? AND dispatch_id = ?", tenantId, dispatchId);
        jdbc.update("DELETE FROM dispatch_lines WHERE tenant_id = ? AND dispatch_id = ?", tenantId, dispatchId);
        jdbc.update("DELETE FROM dispatches WHERE tenant_id = ? AND id = ?", tenantId, dispatchId);

        auditService.record("dispatch", dispatchId, "delete",
                toJson(Map.of("dispatchNumber", dispatch.dispatchNumber())), null);
    }

    // ==========================================
    // PACKING SPECIFICATIONS & RECORDING
    // ==========================================

    public PagedPackingSpecs listPackingSpecs(UUID itemId, Boolean isActive, int page, int size) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        StringBuilder sql = new StringBuilder("""
                SELECT ps.id, ps.item_id, it.code AS item_code, it.name AS item_name,
                       ps.name, ps.packing_type, ps.units_per_pack, ps.gross_weight_kg,
                       ps.dimensions_cm, ps.notes, ps.created_at
                FROM packing_specifications ps
                LEFT JOIN items it ON it.id = ps.item_id AND it.tenant_id = ps.tenant_id
                WHERE ps.tenant_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (itemId != null) {
            params.add(itemId);
            sql.append(" AND ps.item_id = ?");
        }

        sql.append(" ORDER BY ps.created_at DESC");

        List<PackingSpecDto> all = jdbc.query(sql.toString(), (rs, i) -> mapPackingSpec(rs), params.toArray());
        int total = all.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<PackingSpecDto> paged = all.subList(from, to);
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 1;

        return new PagedPackingSpecs(paged, total, totalPages, page, size);
    }

    @Transactional
    public PackingSpecDto createPackingSpec(CreatePackingSpecRequest req) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        if (req.itemId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item ID is required");
        }

        UUID specId = UUID.randomUUID();
        String name = req.packingTypeLabel() != null ? req.packingTypeLabel() : "Standard Packaging";
        String dimJson = null;
        if (req.packDimensions() != null) {
            try {
                dimJson = objectMapper.writeValueAsString(req.packDimensions());
            } catch (JsonProcessingException ignored) {}
        }

        jdbc.update("""
                INSERT INTO packing_specifications (
                  id, tenant_id, item_id, name, packing_type, units_per_pack,
                  gross_weight_kg, dimensions_cm, notes, created_at
                ) VALUES (
                  ?, ?, ?, ?, ?, ?,
                  ?, ?::jsonb, ?, NOW()
                )
                """,
                specId, tenantId, req.itemId(), name, req.packingTypeLabel(),
                req.unitsPerPack(), req.packWeight(), dimJson, req.handlingInstructions()
        );

        return getPackingSpec(specId);
    }

    public PackingSpecDto getPackingSpec(UUID id) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        List<PackingSpecDto> list = jdbc.query("""
                SELECT ps.id, ps.item_id, it.code AS item_code, it.name AS item_name,
                       ps.name, ps.packing_type, ps.units_per_pack, ps.gross_weight_kg,
                       ps.dimensions_cm, ps.notes, ps.created_at
                FROM packing_specifications ps
                LEFT JOIN items it ON it.id = ps.item_id AND it.tenant_id = ps.tenant_id
                WHERE ps.tenant_id = ? AND ps.id = ?
                """, (rs, i) -> mapPackingSpec(rs), tenantId, id);

        if (list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Packing spec not found");
        }
        return list.get(0);
    }

    @Transactional
    public PackingSpecDto updatePackingSpec(UUID id, CreatePackingSpecRequest req) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        String dimJson = null;
        if (req.packDimensions() != null) {
            try {
                dimJson = objectMapper.writeValueAsString(req.packDimensions());
            } catch (JsonProcessingException ignored) {}
        }

        jdbc.update("""
                UPDATE packing_specifications
                SET packing_type = COALESCE(?, packing_type),
                    units_per_pack = COALESCE(?, units_per_pack),
                    gross_weight_kg = COALESCE(?, gross_weight_kg),
                    dimensions_cm = COALESCE(?::jsonb, dimensions_cm),
                    notes = COALESCE(?, notes)
                WHERE tenant_id = ? AND id = ?
                """,
                req.packingTypeLabel(), req.unitsPerPack(), req.packWeight(),
                dimJson, req.handlingInstructions(), tenantId, id
        );

        return getPackingSpec(id);
    }

    @Transactional
    public DispatchPackingRecordDto recordDispatchPacking(UUID dispatchId, RecordPackingRequest req) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        var dispatch = getDispatch(dispatchId);
        if (!"pending".equalsIgnoreCase(dispatch.status()) && !"pending_docs".equalsIgnoreCase(dispatch.status()) && !"draft".equalsIgnoreCase(dispatch.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Packing can only be recorded for pending or draft dispatches");
        }

        UUID pkgId = UUID.randomUUID();
        String pkgCode = "PKG-" + (dispatch.packages().size() + 1);
        String pkgType = req.packingTypeLabel() != null ? req.packingTypeLabel() : "Standard Pack";

        jdbc.update("""
                INSERT INTO dispatch_packages (
                  id, tenant_id, dispatch_id, package_code, package_type, product_description,
                  quantity, uom, weight_kg, packing_notes, created_at
                ) VALUES (
                  ?, ?, ?, ?, ?, ?,
                  ?, 'units', ?, ?, NOW()
                )
                """,
                pkgId, tenantId, dispatchId, pkgCode, pkgType,
                "Packed consignment for " + dispatch.dispatchNumber(),
                req.actualPacksCount() != null ? BigDecimal.valueOf(req.actualPacksCount()) : BigDecimal.ONE,
                req.actualGrossWeight(), req.packingNote()
        );

        // Update dispatch line packing_spec_id if provided
        if (req.packingSpecId() != null) {
            jdbc.update("""
                    UPDATE dispatch_lines
                    SET packing_spec_id = ?
                    WHERE tenant_id = ? AND dispatch_id = ?
                    """, req.packingSpecId(), tenantId, dispatchId);
        }

        auditService.record("dispatch", dispatchId, "packing_recorded", null,
                toJson(Map.of("packageCode", pkgCode, "packsCount", req.actualPacksCount() != null ? req.actualPacksCount() : 1)));

        return new DispatchPackingRecordDto(
                pkgId,
                dispatchId,
                req.packingSpecId(),
                req.packingTypeId(),
                pkgType,
                req.actualPacksCount() != null ? req.actualPacksCount() : 1,
                req.actualGrossWeight(),
                req.packingNote(),
                Instant.now()
        );
    }

    // ==========================================
    // HELPERS
    // ==========================================

    private void validateDispatchMethodOption(UUID tenantId, UUID optionId) {
        if (optionId == null) return;

        Boolean valid = jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1
                  FROM tenant_config_options o
                  JOIN config_categories c ON c.id = o.category_id
                  WHERE o.id = ?
                    AND o.tenant_id = ?
                    AND o.is_active
                    AND c.code = 'DISPATCH_METHOD'
                    AND c.scope = 'tenant'
                    AND c.is_active
                )
                """, Boolean.class, optionId, tenantId);
        if (!Boolean.TRUE.equals(valid)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Dispatch method must be an active option for this tenant");
        }
    }

    private void validateCustomer(UUID tenantId, UUID customerId) {
        Boolean customerExists = jdbc.queryForObject(
                "SELECT COUNT(*) > 0 FROM customers WHERE tenant_id = ? AND id = ?",
                Boolean.class, tenantId, customerId);
        if (!Boolean.TRUE.equals(customerExists)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
        }
    }

    private UUID resolveSiteId(UUID tenantId, UUID siteId) {
        if (siteId == null) {
            return jdbc.queryForObject(
                    "SELECT id FROM physical_sites WHERE tenant_id = ? ORDER BY created_at LIMIT 1",
                    UUID.class, tenantId);
        }
        Boolean siteExists = jdbc.queryForObject(
                "SELECT COUNT(*) > 0 FROM physical_sites WHERE tenant_id = ? AND id = ?",
                Boolean.class, tenantId, siteId);
        if (!Boolean.TRUE.equals(siteExists)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ship-from site not found");
        }
        return siteId;
    }

    private UUID checkQualityGate(UUID tenantId, UUID jobId) {
        if (jobId == null) return null;
        var eligibility = qualityService.computeEligibility(tenantId, jobId);
        if (!eligibility.eligible()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Not eligible for dispatch: " + eligibility.reason());
        }
        return eligibility.clearanceCertificateId();
    }

    private String generateDispatchNumber(UUID tenantId) {
        int currentYear = Year.now().getValue();
        Integer seq = jdbc.queryForObject(
                "SELECT COUNT(*) + 1 FROM dispatches WHERE tenant_id = ? AND dispatch_number LIKE ?",
                Integer.class, tenantId, "DSP-" + currentYear + "-%");
        if (seq == null) seq = 1;
        return String.format("DSP-%d-%03d", currentYear, seq);
    }

    private BigDecimal calculateTotalQty(List<CreateDispatchLineRequest> lines) {
        BigDecimal totalQty = BigDecimal.ZERO;
        if (lines != null) {
            for (var line : lines) {
                totalQty = totalQty.add(line.quantity() != null ? line.quantity() : BigDecimal.ZERO);
            }
        }
        return totalQty;
    }

    private void insertLines(UUID tenantId, UUID dispatchId, UUID defaultJobId, UUID defaultCertId, List<CreateDispatchLineRequest> lines) {
        if (lines == null || lines.isEmpty()) return;
        int lineNo = 1;
        for (var lineReq : lines) {
            UUID lineJobId = lineReq.jobId() != null ? lineReq.jobId() : defaultJobId;
            UUID lineItemId = resolveItemId(tenantId, lineReq.itemId(), lineJobId);
            UUID lineUomId = resolveUomId(tenantId, lineReq.uomId(), lineItemId);

            if (lineReq.cpoLineId() != null) {
                validateCpoLineLimit(tenantId, lineReq.cpoLineId(), lineReq.quantity());
            }

            jdbc.update("""
                    INSERT INTO dispatch_lines (
                      id, tenant_id, dispatch_id, cpo_line_id, item_id, lot_id,
                      quantity, uom_id, packing_spec_id, line_no, job_id, clearance_cert_id, created_at
                    ) VALUES (
                      gen_random_uuid(), ?, ?, ?, ?, ?,
                      ?, ?, ?, ?, ?, ?, NOW()
                    )
                    """,
                    tenantId, dispatchId, lineReq.cpoLineId(), lineItemId, lineReq.lotId(),
                    lineReq.quantity() != null ? lineReq.quantity() : BigDecimal.ONE,
                    lineUomId, lineReq.packingSpecId(), lineNo++, lineJobId,
                    lineReq.clearanceCertId() != null ? lineReq.clearanceCertId() : defaultCertId
            );
        }
    }

    private UUID resolveItemId(UUID tenantId, UUID lineItemId, UUID lineJobId) {
        if (lineItemId != null) return lineItemId;
        if (lineJobId != null) {
            try {
                return jdbc.queryForObject(
                        "SELECT finished_item_id FROM production_jobs WHERE tenant_id = ? AND id = ?",
                        UUID.class, tenantId, lineJobId);
            } catch (EmptyResultDataAccessException ignored) {}
        }
        return null;
    }

    private UUID resolveUomId(UUID tenantId, UUID lineUomId, UUID lineItemId) {
        if (lineUomId != null) return lineUomId;
        if (lineItemId != null) {
            try {
                lineUomId = jdbc.queryForObject(
                        "SELECT base_uom_id FROM items WHERE tenant_id = ? AND id = ?",
                        UUID.class, tenantId, lineItemId);
            } catch (EmptyResultDataAccessException ignored) {}
        }
        if (lineUomId == null) {
            try {
                lineUomId = jdbc.queryForObject(
                        "SELECT id FROM units_of_measure WHERE tenant_id = ? ORDER BY is_base DESC, created_at LIMIT 1",
                        UUID.class, tenantId);
            } catch (EmptyResultDataAccessException ignored) {}
        }
        return lineUomId;
    }

    private void validateCpoLineLimit(UUID tenantId, UUID cpoLineId, BigDecimal reqQty) {
        var cpoLineData = jdbc.queryForMap(
                "SELECT quantity, delivered_qty FROM cpo_lines WHERE tenant_id = ? AND id = ?",
                tenantId, cpoLineId);
        BigDecimal orderedQty = (BigDecimal) cpoLineData.get("quantity");
        BigDecimal alreadyDelivered = (BigDecimal) cpoLineData.get("delivered_qty");
        BigDecimal remaining = orderedQty.subtract(alreadyDelivered != null ? alreadyDelivered : BigDecimal.ZERO);
        if (reqQty != null && reqQty.compareTo(remaining) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Dispatch quantity " + reqQty + " exceeds eligible outstanding quantity " + remaining);
        }
    }

    private void insertPackages(UUID tenantId, UUID dispatchId, List<CreateDispatchPackageRequest> packages) {
        if (packages == null || packages.isEmpty()) return;
        for (var pkg : packages) {
            jdbc.update("""
                    INSERT INTO dispatch_packages (
                      id, tenant_id, dispatch_id, package_code, package_type, product_description,
                      quantity, uom, weight_kg, length_mm, width_mm, height_mm, packing_notes, created_at
                    ) VALUES (
                      gen_random_uuid(), ?, ?, ?, ?, ?,
                      ?, ?, ?, ?, ?, ?, ?, NOW()
                    )
                    """,
                    tenantId, dispatchId, pkg.packageCode(), pkg.packageType(),
                    pkg.productDescription(), pkg.quantity() != null ? pkg.quantity() : BigDecimal.ONE,
                    pkg.uom(), pkg.weightKg(), pkg.lengthMm(), pkg.widthMm(), pkg.heightMm(),
                    pkg.packingNotes()
            );
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private PackingSpecDto mapPackingSpec(ResultSet rs) throws SQLException {
        Dimensions dims = null;
        String dimStr = rs.getString("dimensions_cm");
        if (dimStr != null && !dimStr.isBlank()) {
            try {
                dims = objectMapper.readValue(dimStr, Dimensions.class);
            } catch (Exception ignored) {}
        }

        return new PackingSpecDto(
                (UUID) rs.getObject("id"),
                getUuid(rs, "item_id"),
                rs.getString("item_code"),
                rs.getString("item_name"),
                null,
                rs.getString("packing_type"),
                rs.getInt("units_per_pack"),
                rs.getBigDecimal("gross_weight_kg"),
                "kg",
                dims,
                null,
                rs.getString("notes"),
                true,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private static UUID getUuid(ResultSet rs, String col) throws SQLException {
        Object obj = rs.getObject(col);
        return obj instanceof UUID u ? u : null;
    }

    private static LocalDate getLocalDate(ResultSet rs, String col) throws SQLException {
        java.sql.Date d = rs.getDate(col);
        return d != null ? d.toLocalDate() : null;
    }
}
