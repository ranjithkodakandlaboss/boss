package in.boss.production.domain;

import in.boss.inventory.api.InventoryApiDtos.CreateLotRequest;
import in.boss.inventory.domain.InventoryService;
import in.boss.production.api.ProductionApiDtos.ProductionJobResponse;
import in.boss.production.api.ProductionApiDtos.ReasonRequest;
import in.boss.production.api.ProductionApiDtos.StageActionRequest;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.outbox.OutboxPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static in.boss.shared.security.BossPermissions.OWNER_OR_OPS;
import static in.boss.shared.security.BossPermissions.PRODUCTION_COMPLETE;
import static in.boss.shared.security.BossPermissions.PRODUCTION_EXECUTE;

@Service
@Transactional
public class JobStageService {

    private final JdbcTemplate jdbc;
    private final ProductionSupport support;
    private final ProductionFeatureFlags flags;
    private final ProductionMetrics metrics;
    private final ProductionQueryService query;
    private final JobCostingService costing;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final in.boss.maintenance.domain.MaintenanceService maintenance;
    private final InventoryService inventory;

    public JobStageService(JdbcTemplate jdbc, ProductionSupport support, ProductionFeatureFlags flags,
                           ProductionMetrics metrics, ProductionQueryService query, JobCostingService costing,
                           AuditService audit, OutboxPublisher outbox,
                           in.boss.maintenance.domain.MaintenanceService maintenance,
                           InventoryService inventory) {
        this.jdbc = jdbc;
        this.support = support;
        this.flags = flags;
        this.metrics = metrics;
        this.query = query;
        this.costing = costing;
        this.audit = audit;
        this.outbox = outbox;
        this.maintenance = maintenance;
        this.inventory = inventory;
    }

    @PreAuthorize(PRODUCTION_EXECUTE)
    public ProductionJobResponse start(UUID jobId, UUID stageId, StageActionRequest request) {
        BossContext context = support.requireContext();
        support.denyForeignJob(jdbc, jobId);
        flags.require(context.tenantId(), ProductionFeatureFlags.STAGE_TRANSITION);
        StageRow stage = lockStage(context.tenantId(), jobId, stageId);
        String jobStatus = lockJobStatus(context.tenantId(), jobId);
        requireMaterialsIssued(context.tenantId(), jobId, "start");
        requireRunnable(jobStatus);
        StageStatusMachine.assertTransition(stage.status(), StageStatusMachine.IN_PROGRESS);
        requirePriorClosed(context.tenantId(), jobId, stage.seq());
        requireMachineAvailable(context.tenantId(), jobId, stageId);
        UUID operator = (request != null && request.operatorId() != null) ? request.operatorId() : context.userId();
        jdbc.update("""
                UPDATE job_stages
                SET status = 'in_progress', actual_start = COALESCE(actual_start, NOW()),
                    operator_id = COALESCE(?, operator_id), notes = COALESCE(?, notes)
                WHERE tenant_id = ? AND id = ?
                """, operator, note(request), context.tenantId(), stageId);
        moveJobToProgress(context.tenantId(), jobId, jobStatus);
        audit.record("job_stage", stageId, "START", null, "{\"status\":\"in_progress\"}");
        return query.get(jobId);
    }

    @PreAuthorize(PRODUCTION_COMPLETE)
    public ProductionJobResponse complete(UUID jobId, UUID stageId, StageActionRequest request) {
        BossContext context = support.requireContext();
        support.denyForeignJob(jdbc, jobId);
        flags.require(context.tenantId(), ProductionFeatureFlags.STAGE_TRANSITION);
        StageRow stage = lockStage(context.tenantId(), jobId, stageId);
        String jobStatus = lockJobStatus(context.tenantId(), jobId);
        requireMaterialsIssued(context.tenantId(), jobId, "complete");
        requireRunnable(jobStatus);
        StageStatusMachine.assertTransition(stage.status(), StageStatusMachine.COMPLETED);
        requirePriorClosed(context.tenantId(), jobId, stage.seq());
        enforceInspection(context.tenantId(), stage);
        Instant started = stage.started() == null ? Instant.now() : stage.started();
        UUID completedBy = (request != null && request.operatorId() != null) ? request.operatorId() : context.userId();
        jdbc.update("""
                UPDATE job_stages
                SET status = 'completed', actual_start = COALESCE(actual_start, NOW()),
                    actual_end = NOW(), completed_by = ?,
                    operator_id = COALESCE(?, operator_id),
                    completed_qty = COALESCE(?, completed_qty),
                    blocked_qty   = COALESCE(?, blocked_qty),
                    notes = COALESCE(?, notes)
                WHERE tenant_id = ? AND id = ?
                """, completedBy,
                completedBy,
                request != null ? request.completedQty() : null,
                request != null ? request.blockedQty() : null,
                note(request), context.tenantId(), stageId);
        moveJobToProgress(context.tenantId(), jobId, jobStatus);
        finishJobIfDone(context.tenantId(), jobId);
        audit.record("job_stage", stageId, "COMPLETE", null, "{\"status\":\"completed\"}");
        outbox.publish("production_job", jobId, "production.stage.completed",
                "{\"stageId\":\"" + stageId + "\"}");
        metrics.afterCommit(() -> {
            metrics.stageOutcome("completed");
            metrics.stageDuration(Duration.between(started, Instant.now()));
        });
        return query.get(jobId);
    }

    @PreAuthorize(PRODUCTION_EXECUTE)
    public ProductionJobResponse progress(UUID jobId, UUID stageId, StageActionRequest request) {
        BossContext context = support.requireContext();
        support.denyForeignJob(jdbc, jobId);
        flags.require(context.tenantId(), ProductionFeatureFlags.STAGE_TRANSITION);
        StageRow stage = lockStage(context.tenantId(), jobId, stageId);
        String jobStatus = lockJobStatus(context.tenantId(), jobId);
        requireRunnable(jobStatus);
        // Allow progress on pending (auto-starts) or in_progress stages
        if (!StageStatusMachine.IN_PROGRESS.equals(stage.status())
                && !StageStatusMachine.PENDING.equals(stage.status())) {
            throw ProductionSupport.conflict("Stage status " + stage.status() + " does not accept progress updates");
        }
        requirePriorClosed(context.tenantId(), jobId, stage.seq());
        if (StageStatusMachine.PENDING.equals(stage.status())) {
            requireMachineAvailable(context.tenantId(), jobId, stageId);
        }
        UUID progressOperator = (request != null && request.operatorId() != null) ? request.operatorId() : context.userId();
        jdbc.update("""
                UPDATE job_stages
                SET status = 'in_progress', actual_start = COALESCE(actual_start, NOW()),
                    operator_id = COALESCE(?, operator_id),
                    completed_qty = COALESCE(?, completed_qty),
                    blocked_qty   = COALESCE(?, blocked_qty),
                    notes = COALESCE(?, notes)
                WHERE tenant_id = ? AND id = ?
                """, progressOperator,
                request != null ? request.completedQty() : null,
                request != null ? request.blockedQty() : null,
                note(request), context.tenantId(), stageId);
        moveJobToProgress(context.tenantId(), jobId, jobStatus);
        audit.record("job_stage", stageId, "PROGRESS", null, "{\"status\":\"in_progress\"}");
        return query.get(jobId);
    }

    @PreAuthorize(PRODUCTION_COMPLETE)
    public ProductionJobResponse finish(UUID jobId) {
        BossContext context = support.requireContext();
        support.denyForeignJob(jdbc, jobId);
        flags.require(context.tenantId(), ProductionFeatureFlags.STAGE_TRANSITION);
        String status = lockJobStatus(context.tenantId(), jobId);
        if (!JobStatusMachine.IN_PROGRESS.equals(status)) {
            throw ProductionSupport.conflict("Only an in-progress job can be completed");
        }
        Integer open = jdbc.queryForObject("""
                SELECT COUNT(*) FROM job_stages
                WHERE tenant_id = ? AND job_id = ? AND status NOT IN ('completed', 'skipped')
                """, Integer.class, context.tenantId(), jobId);
        if (open != null && open > 0) {
            throw ProductionSupport.conflict("All job stages must be completed or skipped first");
        }
        requireMaterialsIssued(context.tenantId(), jobId, "complete");
        requireFinalInspection(context.tenantId(), jobId);
        finishJobIfDone(context.tenantId(), jobId);
        return query.get(jobId);
    }

    @PreAuthorize(OWNER_OR_OPS)
    public ProductionJobResponse skip(UUID jobId, UUID stageId, ReasonRequest request) {
        BossContext context = support.requireContext();
        support.denyForeignJob(jdbc, jobId);
        flags.require(context.tenantId(), ProductionFeatureFlags.STAGE_TRANSITION);
        StageRow stage = lockStage(context.tenantId(), jobId, stageId);
        String jobStatus = lockJobStatus(context.tenantId(), jobId);
        if (JobStatusMachine.COMPLETED.equals(jobStatus) || JobStatusMachine.CANCELLED.equals(jobStatus)) {
            throw ProductionSupport.conflict("Job status " + jobStatus + " cannot skip a stage");
        }
        if (!stage.optional()) {
            throw ProductionSupport.badRequest("A mandatory stage cannot be skipped");
        }
        StageStatusMachine.assertTransition(stage.status(), StageStatusMachine.SKIPPED);
        requirePriorClosed(context.tenantId(), jobId, stage.seq());
        jdbc.update("""
                UPDATE job_stages SET status = 'skipped', actual_end = NOW(), notes = ?
                WHERE tenant_id = ? AND id = ?
                """, request.reason().trim(), context.tenantId(), stageId);
        finishJobIfDone(context.tenantId(), jobId);
        audit.record("job_stage", stageId, "SKIP", null, "{\"status\":\"skipped\"}");
        metrics.afterCommit(() -> metrics.stageOutcome("skipped"));
        return query.get(jobId);
    }

    private void enforceInspection(UUID tenantId, StageRow stage) {
        if (!stage.inspection() || !flags.enabled(tenantId, ProductionFeatureFlags.INSPECTION_GATE)) {
            return;
        }
        if (gateOpen(tenantId, stage.id())) {
            metrics.inspectionGate("passed");
            return;
        }
        metrics.inspectionGate("blocked");
        throw ProductionSupport.conflict("Stage inspection must pass before the stage can be completed");
    }

    private boolean gateOpen(UUID tenantId, UUID stageId) {
        Integer unresolvedFailures = jdbc.queryForObject("""
                SELECT COUNT(*) FROM quality_inspections q
                WHERE q.tenant_id = ? AND q.reference_type = 'job_stage' AND q.reference_id = ?
                  AND q.status = 'completed' AND q.result = 'fail'
                  AND (
                    NOT EXISTS (SELECT 1 FROM ncrs n
                                WHERE n.tenant_id = q.tenant_id AND n.inspection_id = q.id)
                    OR EXISTS (
                      SELECT 1 FROM ncrs n
                      WHERE n.tenant_id = q.tenant_id AND n.inspection_id = q.id
                        AND n.status NOT IN ('resolved', 'closed')
                        AND NOT EXISTS (SELECT 1 FROM concessions c
                                        WHERE c.tenant_id = n.tenant_id AND c.ncr_id = n.id
                                          AND c.status = 'approved')
                    )
                  )
                """, Integer.class, tenantId, stageId);
        if (unresolvedFailures != null && unresolvedFailures > 0) {
            return false;
        }
        Integer passed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM quality_inspections
                WHERE tenant_id = ? AND reference_type = 'job_stage' AND reference_id = ?
                  AND status = 'completed' AND result = 'pass'
                """, Integer.class, tenantId, stageId);
        if (passed != null && passed > 0) {
            return true;
        }
        Integer concession = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM concessions c
                JOIN ncrs n ON n.id = c.ncr_id AND n.tenant_id = c.tenant_id
                JOIN quality_inspections q ON q.id = n.inspection_id AND q.tenant_id = n.tenant_id
                WHERE c.tenant_id = ? AND c.status = 'approved'
                  AND q.reference_type = 'job_stage' AND q.reference_id = ?
                """, Integer.class, tenantId, stageId);
        return concession != null && concession > 0;
    }

    private void moveJobToProgress(UUID tenantId, UUID jobId, String status) {
        if (!JobStatusMachine.RELEASED.equals(status)) {
            return;
        }
        JobStatusMachine.assertTransition(status, JobStatusMachine.IN_PROGRESS);
        jdbc.update("""
                UPDATE production_jobs
                SET status = 'in_progress', actual_start = COALESCE(actual_start, NOW()), updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """, tenantId, jobId);
        metrics.afterCommit(() -> metrics.jobTransition(JobStatusMachine.RELEASED, JobStatusMachine.IN_PROGRESS));
    }

    private void finishJobIfDone(UUID tenantId, UUID jobId) {
        Integer open = jdbc.queryForObject("""
                SELECT COUNT(*) FROM job_stages
                WHERE tenant_id = ? AND job_id = ? AND status NOT IN ('completed', 'skipped')
                """, Integer.class, tenantId, jobId);
        if (open != null && open > 0) {
            return;
        }
        requireMaterialsIssued(tenantId, jobId, "complete");
        String status = lockJobStatus(tenantId, jobId);
        if (!JobStatusMachine.IN_PROGRESS.equals(status)) {
            return;
        }
        requireFinalInspection(tenantId, jobId);
        JobStatusMachine.assertTransition(status, JobStatusMachine.COMPLETED);
        jdbc.update("""
                UPDATE production_jobs
                SET status = 'completed', actual_end = NOW(), updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """, tenantId, jobId);
        releaseFinishedGoods(tenantId, jobId);
        costing.close(tenantId, jobId);
        metrics.afterCommit(() -> metrics.jobTransition(JobStatusMachine.IN_PROGRESS, JobStatusMachine.COMPLETED));
        audit.record("production_job", jobId, "STATUS", "{\"status\":\"in_progress\"}",
                "{\"status\":\"completed\"}");
    }

    private void requirePriorClosed(UUID tenantId, UUID jobId, int seq) {
        Integer open = jdbc.queryForObject("""
                SELECT COUNT(*) FROM job_stages
                WHERE tenant_id = ? AND job_id = ? AND seq_no < ? AND status NOT IN ('completed', 'skipped')
                """, Integer.class, tenantId, jobId, seq);
        if (open != null && open > 0) {
            throw ProductionSupport.conflict("Earlier stages must be completed or skipped first");
        }
    }

    private void requireMaterialsIssued(UUID tenantId, UUID jobId, String action) {
        Integer incomplete = jdbc.queryForObject("""
                SELECT COUNT(*) FROM job_material_requirements
                WHERE tenant_id = ? AND job_id = ?
                  AND issued_qty - returned_qty < required_qty
                """, Integer.class, tenantId, jobId);
        if (incomplete != null && incomplete > 0) {
            throw ProductionSupport.conflict(
                    "All required job materials must be issued before a stage can " + action);
        }
    }

    private void requireFinalInspection(UUID tenantId, UUID jobId) {
        Integer passed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM quality_inspections q
                JOIN production_jobs j ON j.id = q.reference_id AND j.tenant_id = q.tenant_id
                WHERE q.tenant_id = ? AND q.reference_type = 'production_job' AND q.reference_id = ?
                  AND q.inspection_type IN ('final', 'dispatch_readiness')
                  AND q.status = 'completed' AND q.result = 'pass'
                  AND q.quantity_accepted >= j.quantity
                  AND NOT EXISTS (
                    SELECT 1 FROM ncrs n
                    WHERE n.tenant_id = q.tenant_id AND n.inspection_id = q.id
                      AND n.status NOT IN ('resolved', 'closed')
                      AND NOT EXISTS (SELECT 1 FROM concessions c
                                      WHERE c.tenant_id = n.tenant_id AND c.ncr_id = n.id
                                        AND c.status = 'approved')
                  )
                """, Integer.class, tenantId, jobId);
        if (passed == null || passed == 0) {
            throw ProductionSupport.conflict(
                    "A passed final inspection covering the full job quantity is required before completion");
        }
    }

    private void releaseFinishedGoods(UUID tenantId, UUID jobId) {
        ProductionOutput output = jdbc.query("""
                SELECT j.job_number, j.finished_item_id, j.site_id, j.cpo_line_id, j.quantity,
                       i.item_type
                FROM production_jobs j
                JOIN items i ON i.id = j.finished_item_id AND i.tenant_id = j.tenant_id
                WHERE j.tenant_id = ? AND j.id = ?
                """, rs -> rs.next() ? new ProductionOutput(rs.getString("job_number"),
                rs.getObject("finished_item_id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getObject("cpo_line_id", UUID.class), rs.getBigDecimal("quantity"),
                rs.getString("item_type")) : null, tenantId, jobId);
        if (output == null) {
            throw ProductionSupport.notFound("Production job not found");
        }
        if (!"finished_goods".equals(output.itemType())) {
            throw ProductionSupport.conflict("Only finished goods can be released to finished-goods inventory");
        }
        UUID storeId = jdbc.query("""
                SELECT id FROM stores
                WHERE tenant_id = ? AND site_id = ? AND store_type = 'finished_goods'
                ORDER BY code LIMIT 1 FOR SHARE
                """, rs -> rs.next() ? rs.getObject("id", UUID.class) : null, tenantId, output.siteId());
        if (storeId == null) {
            throw ProductionSupport.conflict(
                    "Configure a finished-goods store at the production site before completing this job");
        }
        BigDecimal accepted = jdbc.query("""
                SELECT quantity_accepted FROM quality_inspections
                WHERE tenant_id = ? AND reference_type = 'production_job' AND reference_id = ?
                  AND inspection_type IN ('final', 'dispatch_readiness')
                  AND status = 'completed' AND result = 'pass'
                  AND quantity_accepted >= ?
                ORDER BY created_at DESC LIMIT 1
                """, rs -> rs.next() ? rs.getBigDecimal("quantity_accepted") : null,
                tenantId, jobId, output.quantity());
        if (accepted == null) {
            throw ProductionSupport.conflict("Accepted finished quantity is not available for inventory release");
        }
        inventory.createProductionOutputLot(new CreateLotRequest(output.itemId(), storeId, output.quantity(), null,
                "Production " + output.jobNumber(), null, "available", null),
                "FG-" + output.jobNumber(), jobId);
        // Only update CPO produced_qty for PO-linked jobs
        if (output.cpoLineId() != null) {
            int updated = jdbc.update("""
                    UPDATE cpo_lines
                    SET produced_qty = produced_qty + ?
                    WHERE tenant_id = ? AND id = ? AND produced_qty + ? <= quantity
                    """, output.quantity(), tenantId, output.cpoLineId(), output.quantity());
            if (updated != 1) {
                throw ProductionSupport.conflict("Production quantity exceeds the remaining Customer PO line quantity");
            }
        }
    }

    private static void requireRunnable(String status) {
        if (JobStatusMachine.RELEASED.equals(status) || JobStatusMachine.IN_PROGRESS.equals(status)) {
            return;
        }
        throw ProductionSupport.conflict("Job status " + status + " cannot execute a stage");
    }

    private StageRow lockStage(UUID tenantId, UUID jobId, UUID stageId) {
        StageRow stage = jdbc.query("""
                SELECT id, seq_no, status, requires_inspection, is_optional, actual_start
                FROM job_stages WHERE tenant_id = ? AND job_id = ? AND id = ? FOR UPDATE
                """, rs -> rs.next() ? new StageRow(rs.getObject("id", UUID.class), rs.getInt("seq_no"),
                rs.getString("status"), rs.getBoolean("requires_inspection"), rs.getBoolean("is_optional"),
                rs.getTimestamp("actual_start") == null ? null : rs.getTimestamp("actual_start").toInstant()) : null,
                tenantId, jobId, stageId);
        if (stage == null) {
            throw ProductionSupport.notFound("Job stage not found");
        }
        return stage;
    }

    private String lockJobStatus(UUID tenantId, UUID jobId) {
        String status = jdbc.query("""
                SELECT status FROM production_jobs WHERE tenant_id = ? AND id = ? FOR UPDATE
                """, rs -> rs.next() ? rs.getString("status") : null, tenantId, jobId);
        if (status == null) {
            throw ProductionSupport.notFound("Production job not found");
        }
        return status;
    }

    private static String note(StageActionRequest request) {
        if (request == null || request.note() == null || request.note().isBlank()) {
            return null;
        }
        return request.note().trim();
    }

    private void requireMachineAvailable(UUID tenantId, UUID jobId, UUID stageId) {
        List<String> centres = jdbc.query("SELECT work_centre FROM job_stages WHERE tenant_id = ? AND id = ?",
                (rs, i) -> rs.getString(1), tenantId, stageId);
        String blocker = maintenance.machineBlocker(tenantId, jobId, stageId, centres.isEmpty() ? null : centres.get(0));
        if (blocker != null) {
            throw ProductionSupport.conflict("Machine unavailable: open breakdown " + blocker
                    + " blocks this job. Close the maintenance event before starting the stage.");
        }
    }

    private record StageRow(UUID id, int seq, String status, boolean inspection, boolean optional, Instant started) {}

    private record ProductionOutput(String jobNumber, UUID itemId, UUID siteId, UUID cpoLineId,
                                    BigDecimal quantity, String itemType) {}
}
