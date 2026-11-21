package in.boss.dispatch.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.dispatch.api.DispatchDtos.*;
import in.boss.inventory.domain.LotStatus;
import in.boss.inventory.domain.LotStatusMachine;
import in.boss.inventory.domain.MovementType;
import in.boss.inventory.persistence.InventoryRepository;
import in.boss.inventory.persistence.LotRow;
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
    private final InventoryRepository inventoryRepository;
    private final DispatchInventoryCoordinator inventoryCoordinator;
    private final DispatchReader dispatchReader;
    private final QualityService qualityService;
    private final AuditService auditService;
    private final OutboxPublisher outbox;
    private final ObjectMapper objectMapper;

    public DispatchService(
            JdbcTemplate jdbc,
            InventoryRepository inventoryRepository,
            QualityService qualityService,
            AuditService auditService,
            OutboxPublisher outbox,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.inventoryRepository = inventoryRepository;
        this.inventoryCoordinator = new DispatchInventoryCoordinator(jdbc, inventoryRepository);
        this.dispatchReader = new DispatchReader(jdbc);
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

        validateCreateRequest(tenantId, req);
        UUID cpoId = inventoryCoordinator.resolveCpoId(tenantId, req);
        inventoryCoordinator.validateCustomerPo(tenantId, req.customerId(), cpoId);

        UUID siteId = resolveSiteId(tenantId, req.sourceSiteId());
        UUID jobId = inventoryCoordinator.resolveJobId(req);
        UUID clearanceCertId = checkQualityGate(tenantId, jobId);
        inventoryCoordinator.validateCreateLines(tenantId, req, siteId, jobId, cpoId);

        String dispatchNumber = generateDispatchNumber(tenantId);
        String challanNumber = dispatchNumber.replace("DSP-", "CHL-");
        UUID dispatchId = UUID.randomUUID();

        BigDecimal totalQty = calculateTotalQty(req.lines());
        int lineCount = req.lines() != null ? req.lines().size() : 0;

        insertDispatchRecord(ctx, tenantId, dispatchId, dispatchNumber, challanNumber, cpoId, siteId, jobId, clearanceCertId, totalQty, lineCount, req);
        inventoryCoordinator.insertLines(tenantId, dispatchId, jobId, clearanceCertId, req.lines());
        insertPackages(tenantId, dispatchId, req.packages());

        auditService.record("dispatch", dispatchId, "create", null,
                toJson(Map.of("dispatchNumber", dispatchNumber, "status", "pending_docs", "customerId", req.customerId().toString())));
        outbox.publish("dispatch", dispatchId, "dispatch.created",
                toJson(Map.of("dispatchNumber", dispatchNumber, "status", "pending_docs", "tenantId", tenantId)));

        return getDispatch(dispatchId);
    }

    private void validateCreateRequest(UUID tenantId, CreateDispatchRequest req) {
        if (req.customerId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer ID is required");
        }
        if (req.dispatchDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dispatch date is required");
        }
        if (req.lines() == null || req.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one PO line is required");
        }
        validateCustomer(tenantId, req.customerId());
        validateDispatchMethodOption(tenantId, req.dispatchMethodOptionId());
    }

    private void insertDispatchRecord(
            BossContext ctx, UUID tenantId, UUID dispatchId, String dispatchNumber, String challanNumber,
            UUID cpoId, UUID siteId, UUID jobId, UUID clearanceCertId,
            BigDecimal totalQty, int lineCount, CreateDispatchRequest req) {
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
                dispatchId, tenantId, dispatchNumber, cpoId, req.customerId(), siteId,
                "pending_docs", req.dispatchDate(), req.expectedDelivery(), req.vehicleNumber(),
                req.carrier(), req.carrier(), req.driverName(), req.lrNumber(), req.sourceStoreId(),
                req.sourceStoreName(), jobId, clearanceCertId, challanNumber, req.notes(),
                totalQty, lineCount, req.dispatchMethodOptionId(), ctx.userId()
        );
    }

    public List<DispatchSummary> listDispatches(String siteFilter, String statusFilter, String search) {
        return dispatchReader.listDispatches(requireContext().tenantId(), siteFilter, statusFilter, search);
    }

    public DispatchDetail getDispatch(UUID dispatchId) {
        return dispatchReader.getDispatch(requireContext().tenantId(), dispatchId);
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

        if (!DispatchStatusMachine.allows(oldStatus, nextStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid status transition from " + oldStatus + " to " + nextStatus);
        }

        if ("dispatched".equals(nextStatus)) {
            List<String> orderStatuses = jdbc.query("""
                    SELECT c.status FROM cpos c
                    WHERE c.tenant_id = ? AND (
                        c.id = (SELECT d.cpo_id FROM dispatches d WHERE d.tenant_id = c.tenant_id AND d.id = ?)
                        OR EXISTS (
                            SELECT 1 FROM dispatch_lines dl
                            JOIN cpo_lines cl ON cl.tenant_id = dl.tenant_id AND cl.id = dl.cpo_line_id
                            WHERE dl.tenant_id = c.tenant_id AND dl.dispatch_id = ? AND cl.cpo_id = c.id
                        )
                    )
                    ORDER BY c.id FOR UPDATE
                    """, (rs, row) -> rs.getString("status"), tenantId, dispatchId, dispatchId);
            orderStatuses.forEach(DispatchService::requireDispatchableOrder);
            if (current.jobId() == null || current.qualityReleaseId() == null
                    || !"issued".equals(current.qualityReleaseStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cannot dispatch without an issued quality clearance certificate");
            }
            var eligibility = qualityService.computeEligibility(tenantId, current.jobId());
            if (!eligibility.eligible()
                    || !current.qualityReleaseId().equals(eligibility.clearanceCertificateId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cannot dispatch: Quality inspection gate active. " + eligibility.reason());
            }
            inventoryCoordinator.consumeFinishedGoods(current, tenantId, ctx.userId());
        }

        DispatchStatusMachine.apply(jdbc, tenantId, dispatchId, nextStatus, current.lines());

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
        if (jobId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A production job is required for dispatch");
        }
        var eligibility = qualityService.computeEligibility(tenantId, jobId);
        if (!eligibility.eligible()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Not eligible for dispatch: " + eligibility.reason());
        }
        if (eligibility.clearanceCertificateId() == null
                || !"issued".equals(eligibility.clearanceCertificateStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Customer clearance certificate has not been issued for this job");
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

    public PoLineAvailability getPoLineAvailability(UUID cpoLineId) {
        return inventoryCoordinator.getPoLineAvailability(requireContext().tenantId(), cpoLineId);
    }

    static void requireDispatchableOrder(String status) {
        if (!Set.of("open", "partial", "feasibility_approved").contains(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Customer PO status " + status + " cannot be dispatched");
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
}
