package in.boss.production.domain;

import in.boss.production.api.ProductionApiDtos.PlanPreviewResponse;
import in.boss.production.api.ProductionApiDtos.PlanPreviewStage;
import in.boss.production.api.ProductionApiDtos.ProductionJobResponse;
import in.boss.production.api.ProductionApiDtos.SavePlanRequest;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static in.boss.shared.security.BossPermissions.PRODUCTION_WRITE;

@Service
@Transactional
public class JobPlanService {

    private static final int WORK_MINUTES_PER_DAY = 480; // 8 h

    private final JdbcTemplate jdbc;
    private final ProductionSupport support;
    private final ProductionQueryService query;
    private final AuditService audit;

    public JobPlanService(JdbcTemplate jdbc, ProductionSupport support,
                          ProductionQueryService query, AuditService audit) {
        this.jdbc = jdbc;
        this.support = support;
        this.query = query;
        this.audit = audit;
    }

    // ── Plan preview (GET — no DB write) ─────────────────────────────────────

    public PlanPreviewResponse preview(UUID jobId, LocalDate deliveryDate) {
        BossContext context = support.requireContext();
        support.denyForeignJob(jdbc, jobId);

        List<StageDuration> stageDurations = loadStageDurations(context.tenantId(), jobId);
        if (stageDurations.isEmpty()) {
            throw ProductionSupport.badRequest("Job has no stages to plan");
        }

        List<PlanPreviewStage> result = backwardSchedule(stageDurations, deliveryDate);
        return new PlanPreviewResponse(result);
    }

    /** Preview from a routing template directly — used before a job exists (creation modal). */
    public PlanPreviewResponse previewForTemplate(UUID routingTemplateId, LocalDate deliveryDate) {
        BossContext context = support.requireContext();

        List<StageDuration> stageDurations = jdbc.query("""
                SELECT id, seq_no, name AS stage_name,
                       estimated_duration_min
                FROM routing_template_stages
                WHERE tenant_id = ? AND routing_id = ?
                ORDER BY seq_no
                """, (rs, row) -> new StageDuration(
                        rs.getObject("id", UUID.class),
                        rs.getString("stage_name"),
                        rs.getInt("seq_no"),
                        rs.getInt("estimated_duration_min")),
                context.tenantId(), routingTemplateId);

        if (stageDurations.isEmpty()) {
            throw ProductionSupport.badRequest("Routing template has no stages");
        }

        List<PlanPreviewStage> result = backwardSchedule(stageDurations, deliveryDate);
        return new PlanPreviewResponse(result);
    }

    // ── Save plan ─────────────────────────────────────────────────────────────

    @PreAuthorize(PRODUCTION_WRITE)
    public ProductionJobResponse save(UUID jobId, SavePlanRequest request) {
        BossContext context = support.requireContext();
        support.denyForeignJob(jdbc, jobId);

        for (var entry : request.stages()) {
            int updated = jdbc.update("""
                    UPDATE job_stages
                    SET planned_start = ?, planned_end = ?,
                        operator_id = COALESCE(?, operator_id),
                        work_centre = COALESCE(?, work_centre),
                        estimated_duration_min = COALESCE(?, estimated_duration_min),
                        updated_at = NOW()
                    WHERE tenant_id = ? AND job_id = ? AND id = ?
                    """, entry.plannedStart(), entry.plannedEnd(), entry.operatorId(),
                    entry.workCentre(), entry.estimatedDurationMin(),
                    context.tenantId(), jobId, entry.stageId());
            if (updated == 0) {
                throw ProductionSupport.badRequest("Stage " + entry.stageId() + " not found on this job");
            }
        }

        audit.record("production_job", jobId, "PLAN_SAVED", null,
                "{\"stageCount\":" + request.stages().size() + "}");
        return query.get(jobId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<StageDuration> loadStageDurations(UUID tenantId, UUID jobId) {
        return jdbc.query("""
                SELECT s.id, s.seq_no, s.name AS stage_name,
                       COALESCE(NULLIF(s.estimated_duration_min, 0), rts.estimated_duration_min, 0) AS estimated_duration_min
                FROM job_stages s
                LEFT JOIN routing_template_stages rts
                  ON rts.tenant_id = s.tenant_id
                  AND rts.routing_id = (SELECT routing_id FROM production_jobs
                                        WHERE tenant_id = s.tenant_id AND id = s.job_id)
                  AND rts.seq_no = s.seq_no
                WHERE s.tenant_id = ? AND s.job_id = ?
                ORDER BY s.seq_no
                """, (rs, row) -> new StageDuration(
                        rs.getObject("id", UUID.class),
                        rs.getString("stage_name"),
                        rs.getInt("seq_no"),
                        rs.getInt("estimated_duration_min")),
                tenantId, jobId);
    }

    /** Backward schedule: assign dates working from deliveryDate backwards. */
    private static List<PlanPreviewStage> backwardSchedule(List<StageDuration> stages, LocalDate deliveryDate) {
        // Calculate how many working days each stage needs (minimum 1)
        int[] days = new int[stages.size()];
        for (int i = 0; i < stages.size(); i++) {
            int min = stages.get(i).estimatedDurationMin();
            days[i] = Math.max(1, (int) Math.ceil((double) min / WORK_MINUTES_PER_DAY));
        }

        // Walk backwards from delivery date, assigning end → start per stage
        LocalDate cursor = skipWeekend(deliveryDate);
        List<PlanPreviewStage> result = new ArrayList<>(stages.size());

        for (int i = stages.size() - 1; i >= 0; i--) {
            LocalDate end = cursor;
            LocalDate start = subtractWorkingDays(cursor, days[i] - 1);
            result.add(0, new PlanPreviewStage(
                    stages.get(i).id(), stages.get(i).stageName(), stages.get(i).seqNo(),
                    stages.get(i).estimatedDurationMin(), start, end));
            cursor = subtractWorkingDays(start, 1); // 1 day gap before prior stage
        }
        return result;
    }

    private static LocalDate skipWeekend(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY) return date.minusDays(1);
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) return date.minusDays(2);
        return date;
    }

    private static LocalDate subtractWorkingDays(LocalDate from, int workingDays) {
        LocalDate d = from;
        int remaining = workingDays;
        while (remaining > 0) {
            d = d.minusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                remaining--;
            }
        }
        return d;
    }

    private record StageDuration(UUID id, String stageName, int seqNo, int estimatedDurationMin) {}
}
