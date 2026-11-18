package in.boss.production.domain;

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

import java.time.Duration;
import java.time.Instant;
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

    public JobStageService(JdbcTemplate jdbc, ProductionSupport support, ProductionFeatureFlags flags,
                           ProductionMetrics metrics, ProductionQueryService query, JobCostingService costing,
                           AuditService audit, OutboxPublisher outbox) {
        this.jdbc = jdbc;
        this.support = support;
        this.flags = flags;
        this.metrics = metrics;
        this.query = query;
        this.costing = costing;
        this.audit = audit;
        this.outbox = outbox;
    }

    @PreAuthorize(PRODUCTION_EXECUTE)
    public ProductionJobResponse start(UUID jobId, UUID stageId, StageActionRequest request) {
        BossContext context = support.requireContext();
        support.denyForeignJob(jdbc, jobId);
        flags.require(context.tenantId(), ProductionFeatureFlags.STAGE_TRANSITION);
        StageRow stage = lockStage(context.tenantId(), jobId, stageId);
        String jobStatus = lockJobStatus(context.tenantId(), jobId);
        requireRunnable(jobStatus);
        StageStatusMachine.assertTransition(stage.status(), StageStatusMachine.IN_PROGRESS);
        requirePriorClosed(context.tenantId(), jobId, stage.seq());
        jdbc.update("""
                UPDATE job_stages
                SET status = 'in_progress', actual_start = COALESCE(actual_start, NOW()),
                    operator_id = ?, notes = COALESCE(?, notes)
                WHERE tenant_id = ? AND id = ?
                """, context.userId(), note(request), context.tenantId(), stageId);
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
        requireRunnable(jobStatus);
        StageStatusMachine.assertTransition(stage.status(), StageStatusMachine.COMPLETED);
        requirePriorClosed(context.tenantId(), jobId, stage.seq());
        enforceInspection(context.tenantId(), stage);
        Instant started = stage.started() == null ? Instant.now() : stage.started();
        jdbc.update("""
                UPDATE job_stages
                SET status = 'completed', actual_start = COALESCE(actual_start, NOW()),
                    actual_end = NOW(), completed_by = ?, notes = COALESCE(?, notes)
                WHERE tenant_id = ? AND id = ?
                """, context.userId(), note(request), context.tenantId(), stageId);
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
        String status = lockJobStatus(tenantId, jobId);
        if (!JobStatusMachine.IN_PROGRESS.equals(status)) {
            return;
        }
        JobStatusMachine.assertTransition(status, JobStatusMachine.COMPLETED);
        jdbc.update("""
                UPDATE production_jobs
                SET status = 'completed', actual_end = NOW(), updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """, tenantId, jobId);
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

    private record StageRow(UUID id, int seq, String status, boolean inspection, boolean optional, Instant started) {}
}
