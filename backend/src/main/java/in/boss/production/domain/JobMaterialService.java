package in.boss.production.domain;

import in.boss.inventory.api.InventoryApiDtos.AddMovementRequest;
import in.boss.inventory.api.InventoryApiDtos.MaterialLotResponse;
import in.boss.inventory.domain.InventoryService;
import in.boss.inventory.domain.InventoryValidationException;
import in.boss.inventory.domain.InvalidLotTransitionException;
import in.boss.inventory.domain.LotNotFoundException;
import in.boss.production.api.ProductionApiDtos.IssueMaterialRequest;
import in.boss.production.api.ProductionApiDtos.MaterialMovementResponse;
import in.boss.production.api.ProductionApiDtos.ReturnMaterialRequest;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.outbox.OutboxPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static in.boss.shared.security.BossPermissions.PRODUCTION_WRITE;

@Service
@Transactional
public class JobMaterialService {

    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final JobCostingService costing;
    private final ProductionSupport support;
    private final ProductionFeatureFlags flags;
    private final ProductionMetrics metrics;
    private final AuditService audit;
    private final OutboxPublisher outbox;

    public JobMaterialService(JdbcTemplate jdbc, InventoryService inventory, JobCostingService costing,
                              ProductionSupport support, ProductionFeatureFlags flags,
                              ProductionMetrics metrics, AuditService audit, OutboxPublisher outbox) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.costing = costing;
        this.support = support;
        this.flags = flags;
        this.metrics = metrics;
        this.audit = audit;
        this.outbox = outbox;
    }

    @PreAuthorize(PRODUCTION_WRITE)
    public MaterialMovementResponse issue(UUID jobId, IssueMaterialRequest request) {
        return move("issue", call -> issueStock(jobId, request, call));
    }

    @PreAuthorize(PRODUCTION_WRITE)
    public MaterialMovementResponse returnMaterial(UUID jobId, ReturnMaterialRequest request) {
        return move("return", call -> returnStock(jobId, request, call));
    }

    private MaterialMovementResponse move(String type, MovementWork work) {
        boolean[] inventoryCall = {false};
        try {
            MaterialMovementResponse response = work.run(inventoryCall);
            metrics.afterCommit(() -> {
                metrics.material(type, "success");
                metrics.inventory(type, "success");
            });
            return response;
        } catch (InventoryValidationException | InvalidLotTransitionException ex) {
            fail(type, inventoryCall[0]);
            throw ProductionSupport.badRequest(ex.getMessage());
        } catch (LotNotFoundException ex) {
            fail(type, inventoryCall[0]);
            throw ProductionSupport.notFound("Material lot was not found");
        } catch (RuntimeException ex) {
            fail(type, inventoryCall[0]);
            throw ex;
        }
    }

    private MaterialMovementResponse issueStock(UUID jobId, IssueMaterialRequest request, boolean[] inventoryCall) {
        BossContext context = support.requireContext();
        support.denyForeignJob(jdbc, jobId);
        flags.require(context.tenantId(), ProductionFeatureFlags.MATERIAL_ISSUE);
        JobRow job = lockJob(context.tenantId(), jobId);
        rejectClosed(job.status());
        Requirement requirement = lockRequirement(context.tenantId(), jobId, request.requirementId());
        BigDecimal qty = ProductionSupport.quantity(request.qty());
        BigDecimal remaining = requirement.required().subtract(requirement.issued()).add(requirement.returned());
        if (qty.compareTo(remaining) > 0) {
            throw ProductionSupport.badRequest("Issue quantity exceeds the remaining requirement");
        }
        MaterialLotResponse lot = usableLot(request.lotId(), requirement.itemId(), job.siteId(), jobId, qty);
        UUID issueId = UUID.randomUUID();
        inventoryCall[0] = true;
        var movement = inventory.addMovement(lot.id(), new AddMovementRequest(
                "issue", qty, "production_job", issueId, request.note()));
        insertMovement(context, jobId, requirement.id(), null, lot, issueId, "issue", qty,
                request.note(), movement.id(), lot.unitCost());
        jdbc.update("""
                UPDATE job_material_requirements SET issued_qty = issued_qty + ?
                WHERE tenant_id = ? AND id = ?
                """, qty, context.tenantId(), requirement.id());
        releaseJob(context.tenantId(), job);
        costing.refresh(context.tenantId(), jobId);
        audit.record("job_material_issue", issueId, "ISSUE", null, "{\"quantity\":\"" + qty + "\"}");
        outbox.publish("production_job", jobId, "production.material.issued",
                "{\"issueId\":\"" + issueId + "\"}");
        return new MaterialMovementResponse(issueId, movement.id(), requirement.id(), null, lot.id(),
                "issue", qty, lot.unitCost(), java.time.Instant.now());
    }

    private MaterialMovementResponse returnStock(UUID jobId, ReturnMaterialRequest request, boolean[] inventoryCall) {
        BossContext context = support.requireContext();
        support.denyForeignJob(jdbc, jobId);
        flags.require(context.tenantId(), ProductionFeatureFlags.MATERIAL_RETURN);
        JobRow job = lockJob(context.tenantId(), jobId);
        rejectClosed(job.status());
        IssueRow source = lockIssue(context.tenantId(), jobId, request.sourceIssueId());
        BigDecimal qty = ProductionSupport.quantity(request.qty());
        BigDecimal already = returnedAgainst(context.tenantId(), source.id());
        if (already.add(qty).compareTo(source.quantity()) > 0) {
            throw ProductionSupport.badRequest("Return quantity exceeds the quantity issued");
        }
        MaterialLotResponse lot = usableReturnLot(source.lotId(), job.siteId());
        UUID returnId = UUID.randomUUID();
        inventoryCall[0] = true;
        var movement = inventory.addMovement(lot.id(), new AddMovementRequest(
                "return", qty, "production_job", returnId, request.note()));
        insertMovement(context, jobId, source.requirementId(), source.id(), lot, returnId, "return",
                qty, request.note(), movement.id(), source.unitCost());
        jdbc.update("""
                UPDATE job_material_requirements SET returned_qty = returned_qty + ?
                WHERE tenant_id = ? AND id = ?
                """, qty, context.tenantId(), source.requirementId());
        costing.refresh(context.tenantId(), jobId);
        audit.record("job_material_issue", returnId, "RETURN", null, "{\"quantity\":\"" + qty + "\"}");
        outbox.publish("production_job", jobId, "production.material.returned",
                "{\"returnId\":\"" + returnId + "\"}");
        return new MaterialMovementResponse(returnId, movement.id(), source.requirementId(), source.id(),
                lot.id(), "return", qty, source.unitCost(), java.time.Instant.now());
    }

    private MaterialLotResponse usableLot(UUID lotId, UUID itemId, UUID siteId, UUID jobId, BigDecimal qty) {
        MaterialLotResponse lot = inventory.getLot(lotId);
        if (!itemId.equals(lot.itemId()) || !siteId.equals(lot.siteId())) {
            throw ProductionSupport.badRequest("Lot does not match the requirement and job site");
        }
        if (!"available".equals(lot.status()) && !"reserved".equals(lot.status())) {
            throw ProductionSupport.badRequest("Lot status " + lot.status() + " cannot be issued to production");
        }
        if (qty.compareTo(usableQuantity(lot, jobId)) > 0) {
            throw ProductionSupport.badRequest("Usable available stock is not sufficient");
        }
        return lot;
    }

    private MaterialLotResponse usableReturnLot(UUID lotId, UUID siteId) {
        MaterialLotResponse lot = inventory.getLot(lotId);
        if (!siteId.equals(lot.siteId())) {
            throw ProductionSupport.badRequest("Returned lot is not at the job site");
        }
        if ("disposed".equals(lot.status()) || "quarantine".equals(lot.status())) {
            throw ProductionSupport.badRequest("Lot status " + lot.status() + " cannot accept a production return");
        }
        return lot;
    }

    private BigDecimal usableQuantity(MaterialLotResponse lot, UUID jobId) {
        BossContext context = support.requireContext();
        BigDecimal others = sum("""
                SELECT COALESCE(SUM(reserved_qty), 0) FROM stock_reservation_records
                WHERE tenant_id = ? AND lot_id = ? AND status = 'active'
                  AND NOT (reference_type = 'production_job' AND reference_id = ?)
                """, context.tenantId(), lot.id(), jobId);
        BigDecimal recorded = sum("""
                SELECT COALESCE(SUM(reserved_qty), 0) FROM stock_reservation_records
                WHERE tenant_id = ? AND lot_id = ? AND status = 'active'
                """, context.tenantId(), lot.id());
        BigDecimal reserved = lot.reservedQty() == null ? BigDecimal.ZERO : lot.reservedQty();
        BigDecimal unattributed = reserved.subtract(recorded).max(BigDecimal.ZERO);
        BigDecimal blocked = others.add(unattributed);
        return lot.qty().subtract(blocked);
    }

    private void insertMovement(BossContext context, UUID jobId, UUID requirementId, UUID sourceId,
                                MaterialLotResponse lot, UUID id, String type, BigDecimal qty,
                                String note, UUID movementId, BigDecimal unitCost) {
        jdbc.update("""
                INSERT INTO job_material_issues
                  (id, tenant_id, job_id, requirement_id, item_id, lot_id, quantity, uom_id,
                   issue_type, store_id, issued_by, notes, movement_id, unit_cost, source_issue_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, context.tenantId(), jobId, requirementId, lot.itemId(), lot.id(), qty, lot.uomId(),
                type, lot.storeId(), context.userId(), note, movementId, unitCost, sourceId);
    }

    private void releaseJob(UUID tenantId, JobRow job) {
        if (!JobStatusMachine.PLANNED.equals(job.status())) {
            return;
        }
        JobStatusMachine.assertTransition(job.status(), JobStatusMachine.RELEASED);
        jdbc.update("""
                UPDATE production_jobs SET status = 'released', updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """, tenantId, job.id());
        metrics.afterCommit(() -> metrics.jobTransition(JobStatusMachine.PLANNED, JobStatusMachine.RELEASED));
    }

    private JobRow lockJob(UUID tenantId, UUID jobId) {
        JobRow job = jdbc.query("""
                SELECT id, status, site_id FROM production_jobs
                WHERE tenant_id = ? AND id = ? FOR UPDATE
                """, rs -> rs.next() ? new JobRow(rs.getObject("id", UUID.class),
                rs.getString("status"), rs.getObject("site_id", UUID.class)) : null, tenantId, jobId);
        if (job == null) {
            throw ProductionSupport.notFound("Production job not found");
        }
        return job;
    }

    private Requirement lockRequirement(UUID tenantId, UUID jobId, UUID requirementId) {
        Requirement row = jdbc.query("""
                SELECT id, item_id, required_qty, issued_qty, returned_qty
                FROM job_material_requirements
                WHERE tenant_id = ? AND job_id = ? AND id = ? FOR UPDATE
                """, rs -> rs.next() ? new Requirement(rs.getObject("id", UUID.class),
                rs.getObject("item_id", UUID.class), rs.getBigDecimal("required_qty"),
                rs.getBigDecimal("issued_qty"), rs.getBigDecimal("returned_qty")) : null,
                tenantId, jobId, requirementId);
        if (row == null) {
            throw ProductionSupport.badRequest("Material requirement is not on this job");
        }
        return row;
    }

    private IssueRow lockIssue(UUID tenantId, UUID jobId, UUID issueId) {
        IssueRow row = jdbc.query("""
                SELECT id, requirement_id, lot_id, quantity, unit_cost, issue_type
                FROM job_material_issues
                WHERE tenant_id = ? AND job_id = ? AND id = ? FOR UPDATE
                """, rs -> rs.next() ? new IssueRow(rs.getObject("id", UUID.class),
                rs.getObject("requirement_id", UUID.class), rs.getObject("lot_id", UUID.class),
                rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_cost"),
                rs.getString("issue_type")) : null, tenantId, jobId, issueId);
        if (row == null || !"issue".equals(row.type())) {
            throw ProductionSupport.badRequest("Return must reference a material issue on this job");
        }
        return row;
    }

    private BigDecimal returnedAgainst(UUID tenantId, UUID issueId) {
        return sum("""
                SELECT COALESCE(SUM(quantity), 0) FROM job_material_issues
                WHERE tenant_id = ? AND source_issue_id = ? AND issue_type = 'return'
                """, tenantId, issueId);
    }

    private static void rejectClosed(String status) {
        if (JobStatusMachine.COMPLETED.equals(status) || JobStatusMachine.CANCELLED.equals(status)) {
            throw ProductionSupport.conflict("Materials cannot move on a " + status + " job");
        }
    }

    private void fail(String type, boolean attempted) {
        metrics.material(type, "failure");
        if (attempted) {
            metrics.inventory(type, "failure");
        }
    }

    private BigDecimal sum(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }

    @FunctionalInterface
    private interface MovementWork {
        MaterialMovementResponse run(boolean[] inventoryCall);
    }

    private record JobRow(UUID id, String status, UUID siteId) {}

    private record Requirement(UUID id, UUID itemId, BigDecimal required, BigDecimal issued, BigDecimal returned) {}

    private record IssueRow(UUID id, UUID requirementId, UUID lotId, BigDecimal quantity,
                            BigDecimal unitCost, String type) {}
}
