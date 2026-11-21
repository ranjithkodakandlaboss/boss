package in.boss.production.domain;

import in.boss.production.api.ProductionApiDtos.CostSummary;
import in.boss.production.api.ProductionApiDtos.InspectionResponse;
import in.boss.production.api.ProductionApiDtos.JobStageResponse;
import in.boss.production.api.ProductionApiDtos.MaintenanceActivity;
import in.boss.production.api.ProductionApiDtos.MaterialMovementResponse;
import in.boss.production.api.ProductionApiDtos.MaterialRequirementResponse;
import in.boss.production.api.ProductionApiDtos.ProductionJobResponse;
import in.boss.maintenance.domain.MaintenanceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Component
class ProductionJobView {

    private final JdbcTemplate jdbc;
    private final ProductionSupport support;
    private final ProductionMaterialReadiness readiness;
    private final JobCostingService costing;
    private final MaintenanceService maintenance;

    ProductionJobView(JdbcTemplate jdbc, ProductionSupport support, ProductionMaterialReadiness readiness,
                      JobCostingService costing, MaintenanceService maintenance) {
        this.jdbc = jdbc;
        this.support = support;
        this.readiness = readiness;
        this.costing = costing;
        this.maintenance = maintenance;
    }

    ProductionJobResponse map(ResultSet rs, int row) throws SQLException {
        UUID tenantId = rs.getObject("tenant_id", UUID.class);
        UUID jobId = rs.getObject("id", UUID.class);
        List<JobStageResponse> stages = stages(tenantId, jobId);
        List<MaterialRequirementResponse> materials = materials(tenantId, jobId);
        String materialReadiness = readiness.classify(tenantId, jobId, materials);
        String status = rs.getString("status");
        LocalDate plannedEnd = date(rs, "planned_end");
        Risk risk = risk(status, materialReadiness, plannedEnd);
        boolean showMoney = support.commercial(support.requireContext());
        JobCostingService.JobCostSummary costSummary = showMoney ? costing.summaryForJob(tenantId, jobId) : null;
        CostSummary estimated = showMoney ? costSummary.estimatedCost() : emptyCost();
        CostSummary actual = showMoney ? costSummary.actualCost() : emptyCost();
        BigDecimal variance = showMoney ? costSummary.variance() : null;
        return new ProductionJobResponse(
                jobId, rs.getString("job_number"), rs.getObject("cpo_id", UUID.class),
                rs.getObject("cpo_line_id", UUID.class), rs.getString("cpo_number"),
                rs.getObject("customer_id", UUID.class), rs.getString("customer_name"),
                rs.getObject("finished_item_id", UUID.class), rs.getString("item_code"), rs.getString("item_name"),
                rs.getBigDecimal("quantity"), rs.getString("uom_symbol"), rs.getObject("routing_id", UUID.class),
                rs.getString("routing_name"), rs.getString("routing_version"), status, rs.getString("hold_reason"),
                rs.getString("priority"),
                rs.getObject("site_id", UUID.class), rs.getString("site_name"), date(rs, "planned_start"), plannedEnd,
                instant(rs, "actual_start"), instant(rs, "actual_end"), currentStage(stages), materialReadiness,
                risk.name, risk.daysRemaining, risk.projected, risk.basis, stages, materials,
                movements(tenantId, jobId), inspections(tenantId, jobId), estimated, actual, variance,
                instant(rs, "created_at"), maintenanceActivity(tenantId, jobId));
    }

    private List<MaintenanceActivity> maintenanceActivity(UUID tenantId, UUID jobId) {
        return jdbc.query("""
                WITH activity AS (
                    SELECT action, COALESCE(new_data ->> 'eventNumber', 'Maintenance event') AS event_number,
                           created_at AS at
                    FROM audit_events
                    WHERE tenant_id = ? AND entity_type = 'production_job' AND entity_id = ?
                      AND action IN ('MACHINE_BLOCK', 'MACHINE_UNBLOCK')
                    UNION ALL
                    SELECT 'MACHINE_BLOCK', e.event_number, e.created_at
                    FROM maintenance_events e
                    WHERE e.tenant_id = ? AND e.event_type = 'Breakdown'
                      AND (? = ANY(e.affected_job_ids) OR EXISTS (
                          SELECT 1 FROM job_stages s
                          WHERE s.tenant_id = e.tenant_id AND s.job_id = ? AND s.asset_id = e.asset_id
                      ))
                    UNION ALL
                    SELECT 'MACHINE_UNBLOCK', e.event_number, e.completion_date
                    FROM maintenance_events e
                    WHERE e.tenant_id = ? AND e.event_type = 'Breakdown' AND e.status = 'closed'
                      AND e.completion_date IS NOT NULL
                      AND (? = ANY(e.affected_job_ids) OR EXISTS (
                          SELECT 1 FROM job_stages s
                          WHERE s.tenant_id = e.tenant_id AND s.job_id = ? AND s.asset_id = e.asset_id
                      ))
                ), deduplicated AS (
                    SELECT DISTINCT ON (action, event_number) action, event_number, at
                    FROM activity
                    ORDER BY action, event_number, at DESC
                )
                SELECT action, event_number, at FROM deduplicated ORDER BY at DESC
                """, (rs, row) -> new MaintenanceActivity(rs.getString("action"), rs.getString("event_number"),
                instant(rs, "at")), tenantId, jobId, tenantId, jobId, jobId, tenantId, jobId, jobId);
    }

    private List<JobStageResponse> stages(UUID tenantId, UUID jobId) {
        return jdbc.query("""
                SELECT s.id, s.seq_no, s.name, s.stage_code, s.stage_config_option_id, s.stage_label_snapshot,
                       s.status, s.requires_inspection, s.is_optional, s.work_centre, s.asset_id,
                       a.asset_number, s.actual_start, s.actual_end, s.completed_by,
                       s.planned_start, s.planned_end, s.completed_qty, s.blocked_qty,
                       COALESCE(NULLIF(s.estimated_duration_min, 0), NULLIF(rts.estimated_duration_min, 0), COALESCE(rts.setup_time_min, 0) + COALESCE(rts.run_time_min, 0), 0) AS estimated_duration_min,
                       s.operator_id, u.display_name AS operator_name
                FROM job_stages s
                LEFT JOIN assets a ON a.id = s.asset_id AND a.tenant_id = s.tenant_id
                LEFT JOIN routing_template_stages rts
                  ON rts.tenant_id = s.tenant_id
                  AND rts.routing_id = (SELECT routing_id FROM production_jobs
                                        WHERE tenant_id = s.tenant_id AND id = s.job_id)
                  AND rts.seq_no = s.seq_no
                LEFT JOIN app_users u ON u.id = s.operator_id AND u.tenant_id = s.tenant_id
                WHERE s.tenant_id = ? AND s.job_id = ? ORDER BY s.seq_no
                """, (rs, row) -> stage(tenantId, jobId, rs), tenantId, jobId);
    }

    private JobStageResponse stage(UUID tenantId, UUID jobId, ResultSet rs) throws SQLException {
        UUID stageId = rs.getObject("id", UUID.class);
        boolean required = rs.getBoolean("requires_inspection");
        String label = rs.getString("stage_label_snapshot");
        if (label == null || label.isBlank()) {
            label = rs.getString("name");
        }
        int estMin = rs.getInt("estimated_duration_min");
        Integer estimatedDuration = rs.wasNull() || estMin == 0 ? null : estMin;
        return new JobStageResponse(stageId, rs.getInt("seq_no"), label, rs.getString("stage_code"),
                rs.getObject("stage_config_option_id", UUID.class), rs.getString("status"), required,
                inspectionStatus(tenantId, stageId, required), rs.getBoolean("is_optional"), rs.getBoolean("is_optional"),
                rs.getString("work_centre"), instant(rs, "actual_start"), instant(rs, "actual_end"),
                rs.getObject("completed_by", UUID.class), rs.getObject("asset_id", UUID.class),
                rs.getString("asset_number"),
                maintenance.machineBlocker(tenantId, jobId, stageId, rs.getString("work_centre")),
                date(rs, "planned_start"), date(rs, "planned_end"), estimatedDuration,
                rs.getBigDecimal("completed_qty"), rs.getBigDecimal("blocked_qty"),
                rs.getObject("operator_id", UUID.class), rs.getString("operator_name"));
    }

    private String inspectionStatus(UUID tenantId, UUID stageId, boolean required) {
        if (!required) {
            return "not_required";
        }
        if (approvedConcession(tenantId, stageId)) {
            return "passed";
        }
        String result = jdbc.query("""
                SELECT result FROM quality_inspections
                WHERE tenant_id = ? AND reference_type = 'job_stage' AND reference_id = ?
                ORDER BY created_at DESC LIMIT 1
                """, rs -> rs.next() ? rs.getString("result") : null, tenantId, stageId);
        if ("pass".equals(result)) {
            return "passed";
        }
        return "fail".equals(result) ? "failed" : "pending";
    }

    private boolean approvedConcession(UUID tenantId, UUID stageId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM concessions c
                JOIN ncrs n ON n.id = c.ncr_id AND n.tenant_id = c.tenant_id
                JOIN quality_inspections q ON q.id = n.inspection_id AND q.tenant_id = n.tenant_id
                WHERE c.tenant_id = ? AND q.reference_type = 'job_stage' AND q.reference_id = ?
                  AND c.status = 'approved'
                """, Integer.class, tenantId, stageId);
        return count != null && count > 0;
    }

    private List<MaterialRequirementResponse> materials(UUID tenantId, UUID jobId) {
        return jdbc.query("""
                SELECT r.id, r.item_id, i.code, i.name, i.std_cost,
                       r.required_qty, r.issued_qty, r.returned_qty, u.symbol,
                       CASE WHEN r.issued_qty - r.returned_qty > 0
                            THEN (SELECT SUM(m.quantity * m.unit_cost) / NULLIF(SUM(m.quantity), 0)
                                  FROM job_material_issues m
                                  WHERE m.tenant_id = r.tenant_id AND m.requirement_id = r.id
                                    AND m.issue_type = 'issue' AND m.unit_cost IS NOT NULL)
                            ELSE NULL END AS actual_unit_cost
                FROM job_material_requirements r
                JOIN items i ON i.id = r.item_id AND i.tenant_id = r.tenant_id
                JOIN units_of_measure u ON u.id = r.uom_id
                WHERE r.tenant_id = ? AND r.job_id = ? ORDER BY i.code
                """, (rs, row) -> {
            BigDecimal required = rs.getBigDecimal("required_qty");
            BigDecimal issued = rs.getBigDecimal("issued_qty");
            BigDecimal returned = rs.getBigDecimal("returned_qty");
            BigDecimal net = issued.subtract(returned);
            BigDecimal stdCost = rs.getBigDecimal("std_cost");
            BigDecimal estUnitCost = stdCost;
            BigDecimal estTotalCost = stdCost != null ? stdCost.multiply(required) : null;
            BigDecimal actUnitCost = rs.getBigDecimal("actual_unit_cost");
            BigDecimal actTotalCost = actUnitCost != null && net.signum() > 0
                    ? actUnitCost.multiply(net) : null;
            return new MaterialRequirementResponse(rs.getObject("id", UUID.class),
                    rs.getObject("item_id", UUID.class), rs.getString("code"), rs.getString("name"),
                    required, issued, returned, net, rs.getString("symbol"),
                    ProductionMaterialReadiness.line(net, required),
                    estUnitCost, estTotalCost, actUnitCost, actTotalCost);
        }, tenantId, jobId);
    }

    private List<MaterialMovementResponse> movements(UUID tenantId, UUID jobId) {
        return jdbc.query("""
                SELECT id, movement_id, requirement_id, source_issue_id, lot_id, issue_type,
                       quantity, unit_cost, created_at
                FROM job_material_issues WHERE tenant_id = ? AND job_id = ? ORDER BY created_at
                """, (rs, row) -> new MaterialMovementResponse(rs.getObject("id", UUID.class),
                rs.getObject("movement_id", UUID.class), rs.getObject("requirement_id", UUID.class),
                rs.getObject("source_issue_id", UUID.class), rs.getObject("lot_id", UUID.class),
                rs.getString("issue_type"), rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_cost"),
                instant(rs, "created_at")), tenantId, jobId);
    }

    private List<InspectionResponse> inspections(UUID tenantId, UUID jobId) {
        return jdbc.query("""
                SELECT q.id,
                       CASE WHEN q.reference_type = 'job_stage' THEN q.reference_id END AS stage_id,
                       q.inspection_number, q.inspection_type, q.status, q.result,
                       q.quantity_inspected, q.quantity_accepted, q.quantity_rejected,
                       n.id AS ncr_id, n.status AS ncr_status, c.status AS concession_status
                FROM quality_inspections q
                LEFT JOIN job_stages s ON s.id = q.reference_id AND s.tenant_id = q.tenant_id
                     AND q.reference_type = 'job_stage'
                LEFT JOIN ncrs n ON n.inspection_id = q.id AND n.tenant_id = q.tenant_id
                LEFT JOIN concessions c ON c.ncr_id = n.id AND c.tenant_id = n.tenant_id
                WHERE q.tenant_id = ?
                  AND ((q.reference_type = 'production_job' AND q.reference_id = ?)
                    OR (q.reference_type = 'job_stage' AND s.job_id = ?))
                ORDER BY q.created_at
                """, (rs, row) -> new InspectionResponse(rs.getObject("id", UUID.class),
                rs.getObject("stage_id", UUID.class), rs.getString("inspection_number"),
                rs.getString("inspection_type"), rs.getString("status"), rs.getString("result"),
                rs.getBigDecimal("quantity_inspected"), rs.getBigDecimal("quantity_accepted"),
                rs.getBigDecimal("quantity_rejected"),
                rs.getObject("ncr_id", UUID.class), rs.getString("ncr_status"),
                rs.getString("concession_status")), tenantId, jobId, jobId);
    }

    private static String currentStage(List<JobStageResponse> stages) {
        for (JobStageResponse stage : stages) {
            if (StageStatusMachine.IN_PROGRESS.equals(stage.status())) {
                return stage.stageName();
            }
        }
        for (JobStageResponse stage : stages) {
            if (StageStatusMachine.PENDING.equals(stage.status())) {
                return stage.stageName();
            }
        }
        return stages.isEmpty() ? null : stages.get(stages.size() - 1).stageName();
    }

    private static Risk risk(String status, String materialReadiness, LocalDate plannedEnd) {
        if (JobStatusMachine.COMPLETED.equals(status)) {
            return new Risk("complete", days(plannedEnd), plannedEnd, "Actual completion recorded.");
        }
        if (JobStatusMachine.CANCELLED.equals(status)) {
            return new Risk("complete", 0L, null, "Job cancelled.");
        }
        if (JobStatusMachine.ON_HOLD.equals(status)) {
            return new Risk("blocked", days(plannedEnd), null, "Job is on hold.");
        }
        if (isMaterialRisk(materialReadiness)) {
            return new Risk("awaiting_material", days(plannedEnd), null, "Material is not fully issued to this job.");
        }
        Long remaining = days(plannedEnd);
        if (remaining != null && remaining < 0) {
            return new Risk("delayed", remaining, plannedEnd, "Planned end date has passed.");
        }
        if (remaining != null && remaining <= 3) {
            return new Risk("at_risk", remaining, plannedEnd, "Planned end date is within three days.");
        }
        return new Risk("on_track", remaining, plannedEnd, "Planned end date is still ahead.");
    }

    private static boolean isMaterialRisk(String materialReadiness) {
        return "partial".equals(materialReadiness) || "shortage".equals(materialReadiness)
                || "awaiting_procurement".equals(materialReadiness)
                || "awaiting_grn".equals(materialReadiness) || "quality_hold".equals(materialReadiness);
    }

    private static Long days(LocalDate plannedEnd) {
        return plannedEnd == null ? null : ChronoUnit.DAYS.between(LocalDate.now(ZoneOffset.UTC), plannedEnd);
    }

    private static CostSummary emptyCost() {
        return new CostSummary(null, null, null, null, null, null, null, null, null);
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record Risk(String name, Long daysRemaining, LocalDate projected, String basis) {}
}
