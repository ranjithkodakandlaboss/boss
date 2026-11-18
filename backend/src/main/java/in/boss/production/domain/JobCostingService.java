package in.boss.production.domain;

import in.boss.production.api.ProductionApiDtos.AddCostRequest;
import in.boss.production.api.ProductionApiDtos.CostEntryResponse;
import in.boss.production.api.ProductionApiDtos.CostSummary;
import in.boss.production.api.ProductionApiDtos.CostingResponse;
import in.boss.production.api.ProductionApiDtos.HistoricalCostReference;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static in.boss.shared.security.BossPermissions.COMMERCIAL_DATA_READ;
import static in.boss.shared.security.BossPermissions.COSTING_WRITE;

@Service
@Transactional
public class JobCostingService {

    private static final String HISTORICAL_BASIS =
            "Weighted average actual cost per unit from earlier completed jobs for the same item and routing revision. "
                    + "Only jobs with fully issued, priced materials and recorded labour and overhead are included; "
                    + "job-level specifications are not captured.";

    private final JdbcTemplate jdbc;
    private final ProductionSupport support;
    private final ProductionFeatureFlags flags;
    private final ProductionMetrics metrics;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public JobCostingService(JdbcTemplate jdbc, ProductionSupport support, ProductionFeatureFlags flags,
                             ProductionMetrics metrics, AuditService audit, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.support = support;
        this.flags = flags;
        this.metrics = metrics;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @PreAuthorize(COMMERCIAL_DATA_READ)
    public CostingResponse get(UUID jobId) {
        BossContext context = support.requireContext();
        support.denyForeignJob(jdbc, jobId);
        flags.require(context.tenantId(), ProductionFeatureFlags.JOB_COSTING);
        return load(context.tenantId(), jobId);
    }

    @PreAuthorize(COSTING_WRITE)
    public CostEntryResponse addEntry(UUID jobId, AddCostRequest request) {
        BossContext context = support.requireContext();
        support.denyForeignJob(jdbc, jobId);
        flags.require(context.tenantId(), ProductionFeatureFlags.JOB_COSTING);
        requireJob(context.tenantId(), jobId);
        UUID costingId = costingId(context.tenantId(), jobId);
        String costType = request.entryType().toLowerCase();
        UUID lineId = UUID.randomUUID();
        BigDecimal amount = JobCostCalculator.money(request.amount());
        jdbc.update("""
                INSERT INTO job_costing_lines
                  (id, tenant_id, costing_id, cost_type, description, quantity, unit_cost, amount, is_estimated)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE)
                """, lineId, context.tenantId(), costingId, costType, request.description().trim(),
                request.qty(), request.unitCost(), amount);
        refresh(context.tenantId(), jobId);
        audit.record("job_costing_lines", lineId, "CREATE", null, auditDetails(request, amount));
        return new CostEntryResponse(lineId, request.entryType(), request.description().trim(),
                request.qty(), request.unitCost(), amount, false, Instant.now());
    }

    public void refresh(UUID tenantId, UUID jobId) {
        CostData data = costData(tenantId, jobId);
        if (data == null) {
            throw ProductionSupport.notFound("Job costing not found");
        }
        JobCostSummary summary = summarize(data);
        BigDecimal material = data.actualMaterial() == null ? BigDecimal.ZERO : data.actualMaterial();
        BigDecimal labour = data.actualLabourCount() == 0 ? BigDecimal.ZERO : data.actualLabour();
        BigDecimal overhead = data.actualOverheadCount() == 0 ? BigDecimal.ZERO : data.actualOverhead();
        jdbc.update("""
                UPDATE job_costing_headers
                SET act_material_cost = ?, act_labour_cost = ?, act_overhead_cost = ?,
                    actual_margin_pct = ?, status = 'in_progress', updated_at = NOW()
                WHERE tenant_id = ? AND job_id = ?
                """, JobCostCalculator.money(material), JobCostCalculator.money(labour),
                JobCostCalculator.money(overhead), summary.actualCost().marginPct(), tenantId, jobId);
        metrics.afterCommit(() -> {
            metrics.costing("success");
            if (summary.variance() != null) {
                metrics.variance(summary.variance());
            }
        });
    }

    public void close(UUID tenantId, UUID jobId) {
        refresh(tenantId, jobId);
        jdbc.update("""
                UPDATE job_costing_headers SET status = 'actual', updated_at = NOW()
                WHERE tenant_id = ? AND job_id = ?
                """, tenantId, jobId);
    }

    JobCostSummary summaryForJob(UUID tenantId, UUID jobId) {
        CostData data = costData(tenantId, jobId);
        return data == null ? emptySummary() : summarize(data);
    }

    private CostingResponse load(UUID tenantId, UUID jobId) {
        CostData data = costData(tenantId, jobId);
        if (data == null) {
            throw ProductionSupport.notFound("Job costing not found");
        }
        JobCostSummary summary = summarize(data);
        List<CostEntryResponse> entries = jdbc.query("""
                SELECT l.id, l.cost_type, l.description, l.quantity, l.unit_cost, l.amount,
                       l.is_estimated, l.created_at
                FROM job_costing_lines l
                JOIN job_costing_headers h ON h.id = l.costing_id AND h.tenant_id = l.tenant_id
                WHERE h.tenant_id = ? AND h.job_id = ?
                ORDER BY l.created_at
                """, (rs, row) -> new CostEntryResponse(
                rs.getObject("id", UUID.class),
                rs.getString("cost_type").toUpperCase(),
                rs.getString("description"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_cost"),
                rs.getBigDecimal("amount"),
                rs.getBoolean("is_estimated"),
                rs.getTimestamp("created_at").toInstant()), tenantId, jobId);
        return new CostingResponse(jobId, data.jobNumber(), summary.estimatedCost(), summary.actualCost(),
                summary.variance(), profit(data.sellingPrice(), summary.estimatedCost().totalCost()),
                profit(data.sellingPrice(), summary.actualCost().totalCost()),
                "BOM quantities valued using the item standard cost captured when the job was created. "
                        + "No quotation-level labour, machine or overhead estimate is available for this job.",
                historicalReference(tenantId, jobId), entries);
    }

    private JobCostSummary summarize(CostData data) {
        String estimatedMaterialStatus = data.estimatedMaterialCount() == 0 ? "not_recorded"
                : data.estimatedMaterialMissingCostCount() > 0 ? "missing_unit_cost" : "captured";
        String estimatedLabourStatus = data.estimatedLabourCount() > 0 ? "captured" : "not_recorded";
        String estimatedOverheadStatus = data.estimatedOverheadCount() > 0 ? "captured" : "not_recorded";
        BigDecimal estimatedMaterial = "captured".equals(estimatedMaterialStatus) ? data.estimatedMaterial() : null;
        BigDecimal estimatedLabour = data.estimatedLabourCount() > 0 ? data.estimatedLabour() : null;
        BigDecimal estimatedOverhead = data.estimatedOverheadCount() > 0 ? data.estimatedOverhead() : null;
        BigDecimal estimatedTotal = completeEstimate(estimatedMaterialStatus, estimatedLabourStatus,
                estimatedOverheadStatus)
                ? JobCostCalculator.total(valueOrZero(estimatedMaterial), valueOrZero(estimatedLabour),
                valueOrZero(estimatedOverhead))
                : null;
        CostSummary estimated = new CostSummary(estimatedMaterial, estimatedLabour, estimatedOverhead,
                estimatedTotal, data.sellingPrice(),
                estimatedTotal == null ? null : JobCostCalculator.marginPct(data.sellingPrice(), estimatedTotal),
                estimatedMaterialStatus, estimatedLabourStatus, estimatedOverheadStatus);

        String materialStatus = materialStatus(data);
        String labourStatus = manualCostStatus(data.actualLabourCount(), data.jobStatus());
        String overheadStatus = manualCostStatus(data.actualOverheadCount(), data.jobStatus());
        BigDecimal material = amountIfKnown(data.actualMaterial(), data.movementCount(), materialStatus);
        BigDecimal labour = data.actualLabourCount() > 0 ? data.actualLabour() : null;
        BigDecimal overhead = data.actualOverheadCount() > 0 ? data.actualOverhead() : null;
        boolean actualComplete = "completed".equals(data.jobStatus())
                && completeActual(materialStatus, labourStatus, overheadStatus);
        BigDecimal actualTotal = actualComplete
                ? JobCostCalculator.total(valueOrZero(material), valueOrZero(labour), valueOrZero(overhead))
                : null;
        CostSummary actual = new CostSummary(material, labour, overhead, actualTotal, data.sellingPrice(),
                actualTotal == null ? null : JobCostCalculator.marginPct(data.sellingPrice(), actualTotal),
                materialStatus, labourStatus, overheadStatus);
        BigDecimal variance = estimatedTotal == null || actualTotal == null
                ? null : JobCostCalculator.variance(actualTotal, estimatedTotal);
        return new JobCostSummary(estimated, actual, variance);
    }

    private static String materialStatus(CostData data) {
        if (data.requirementCount() == 0) {
            return "not_applicable";
        }
        if (data.unpricedMovementCount() > 0) {
            return "missing_unit_cost";
        }
        if ("completed".equals(data.jobStatus())) {
            if (data.unissuedRequirementCount() > 0 || data.movementCount() == 0) {
                return "incomplete";
            }
            return "complete";
        }
        return data.movementCount() > 0 ? "captured_to_date" : "not_recorded";
    }

    private static String manualCostStatus(int count, String jobStatus) {
        if (count == 0) {
            return "not_recorded";
        }
        return "completed".equals(jobStatus) ? "complete" : "captured_to_date";
    }

    private static BigDecimal amountIfKnown(BigDecimal amount, int movementCount, String status) {
        if ("not_applicable".equals(status)) {
            return BigDecimal.ZERO;
        }
        if (movementCount == 0 || "missing_unit_cost".equals(status)) {
            return null;
        }
        return amount;
    }

    private static boolean completeEstimate(String material, String labour, String overhead) {
        return "captured".equals(material) && "captured".equals(labour) && "captured".equals(overhead);
    }

    private static boolean completeActual(String material, String labour, String overhead) {
        return ("complete".equals(material) || "not_applicable".equals(material))
                && "complete".equals(labour) && "complete".equals(overhead);
    }

    private static BigDecimal profit(BigDecimal selling, BigDecimal cost) {
        return selling == null || cost == null ? null : JobCostCalculator.profit(selling, cost);
    }

    private static BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String auditDetails(AddCostRequest request, BigDecimal amount) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "entryType", request.entryType(),
                    "description", request.description().trim(),
                    "amount", amount.toPlainString()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize job-cost audit data", exception);
        }
    }

    private static JobCostSummary emptySummary() {
        CostSummary unavailable = new CostSummary(null, null, null, null, null, null,
                "not_recorded", "not_recorded", "not_recorded");
        return new JobCostSummary(unavailable, unavailable, null);
    }

    private CostData costData(UUID tenantId, UUID jobId) {
        return jdbc.query("""
                SELECT j.job_number, j.status, h.selling_price,
                       (SELECT COUNT(*) FROM job_costing_lines l
                        WHERE l.tenant_id = h.tenant_id AND l.costing_id = h.id
                          AND l.is_estimated AND l.cost_type = 'material') AS estimated_material_count,
                       (SELECT COUNT(*) FROM job_costing_lines l
                        WHERE l.tenant_id = h.tenant_id AND l.costing_id = h.id
                          AND l.is_estimated AND l.cost_type = 'material' AND l.unit_cost IS NULL)
                          AS estimated_material_missing_cost_count,
                       (SELECT COUNT(*) FROM job_costing_lines l
                        WHERE l.tenant_id = h.tenant_id AND l.costing_id = h.id
                          AND l.is_estimated AND l.cost_type = 'labour') AS estimated_labour_count,
                       (SELECT COUNT(*) FROM job_costing_lines l
                        WHERE l.tenant_id = h.tenant_id AND l.costing_id = h.id
                          AND l.is_estimated AND l.cost_type IN ('overhead', 'other')) AS estimated_overhead_count,
                       (SELECT COALESCE(SUM(l.amount), 0) FROM job_costing_lines l
                        WHERE l.tenant_id = h.tenant_id AND l.costing_id = h.id
                          AND l.is_estimated AND l.cost_type = 'material') AS estimated_material,
                       (SELECT COALESCE(SUM(l.amount), 0) FROM job_costing_lines l
                        WHERE l.tenant_id = h.tenant_id AND l.costing_id = h.id
                          AND l.is_estimated AND l.cost_type = 'labour') AS estimated_labour,
                       (SELECT COALESCE(SUM(l.amount), 0) FROM job_costing_lines l
                        WHERE l.tenant_id = h.tenant_id AND l.costing_id = h.id
                          AND l.is_estimated AND l.cost_type IN ('overhead', 'other')) AS estimated_overhead,
                       (SELECT COUNT(*) FROM job_material_requirements r
                        WHERE r.tenant_id = j.tenant_id AND r.job_id = j.id) AS requirement_count,
                       (SELECT COUNT(*) FROM job_material_requirements r
                        WHERE r.tenant_id = j.tenant_id AND r.job_id = j.id
                          AND r.issued_qty < r.required_qty) AS unissued_requirement_count,
                       (SELECT COUNT(*) FROM job_material_issues m
                        WHERE m.tenant_id = j.tenant_id AND m.job_id = j.id
                          AND m.issue_type IN ('issue', 'return')) AS movement_count,
                       (SELECT COUNT(*) FROM job_material_issues m
                        WHERE m.tenant_id = j.tenant_id AND m.job_id = j.id
                          AND m.issue_type IN ('issue', 'return') AND m.unit_cost IS NULL) AS unpriced_movement_count,
                       (SELECT COALESCE(SUM(CASE
                           WHEN m.issue_type = 'issue' THEN m.quantity * m.unit_cost
                           WHEN m.issue_type = 'return' THEN -m.quantity * m.unit_cost
                           ELSE 0 END), 0)
                        FROM job_material_issues m
                        WHERE m.tenant_id = j.tenant_id AND m.job_id = j.id
                          AND m.issue_type IN ('issue', 'return') AND m.unit_cost IS NOT NULL) AS actual_material,
                       (SELECT COUNT(*) FROM job_costing_lines l
                        WHERE l.tenant_id = h.tenant_id AND l.costing_id = h.id
                          AND NOT l.is_estimated AND l.cost_type = 'labour') AS actual_labour_count,
                       (SELECT COALESCE(SUM(l.amount), 0) FROM job_costing_lines l
                        WHERE l.tenant_id = h.tenant_id AND l.costing_id = h.id
                          AND NOT l.is_estimated AND l.cost_type = 'labour') AS actual_labour,
                       (SELECT COUNT(*) FROM job_costing_lines l
                        WHERE l.tenant_id = h.tenant_id AND l.costing_id = h.id
                          AND NOT l.is_estimated AND l.cost_type IN ('overhead', 'other')) AS actual_overhead_count,
                       (SELECT COALESCE(SUM(l.amount), 0) FROM job_costing_lines l
                        WHERE l.tenant_id = h.tenant_id AND l.costing_id = h.id
                          AND NOT l.is_estimated AND l.cost_type IN ('overhead', 'other')) AS actual_overhead
                FROM job_costing_headers h
                JOIN production_jobs j ON j.id = h.job_id AND j.tenant_id = h.tenant_id
                WHERE h.tenant_id = ? AND h.job_id = ?
                """, rs -> {
            if (!rs.next()) {
                return null;
            }
            return new CostData(
                    rs.getString("job_number"), rs.getString("status"), rs.getBigDecimal("selling_price"),
                    rs.getInt("estimated_material_count"), rs.getInt("estimated_labour_count"),
                    rs.getInt("estimated_material_missing_cost_count"), rs.getInt("estimated_overhead_count"),
                    rs.getBigDecimal("estimated_material"),
                    rs.getBigDecimal("estimated_labour"), rs.getBigDecimal("estimated_overhead"),
                    rs.getInt("requirement_count"), rs.getInt("unissued_requirement_count"),
                    rs.getInt("movement_count"), rs.getInt("unpriced_movement_count"),
                    rs.getBigDecimal("actual_material"), rs.getInt("actual_labour_count"),
                    rs.getBigDecimal("actual_labour"), rs.getInt("actual_overhead_count"),
                    rs.getBigDecimal("actual_overhead"));
        }, tenantId, jobId);
    }

    private HistoricalCostReference historicalReference(UUID tenantId, UUID jobId) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT prior.tenant_id, prior.id, prior.quantity
                    FROM production_jobs prior
                    JOIN production_jobs current_job
                      ON current_job.tenant_id = prior.tenant_id AND current_job.id = ?
                    WHERE prior.tenant_id = ?
                      AND prior.id <> current_job.id
                      AND prior.finished_item_id = current_job.finished_item_id
                      AND prior.routing_id IS NOT DISTINCT FROM current_job.routing_id
                      AND prior.routing_version IS NOT DISTINCT FROM current_job.routing_version
                      AND prior.status = 'completed'
                      AND prior.created_at < current_job.created_at
                      AND prior.quantity > 0
                      AND EXISTS (SELECT 1 FROM job_material_requirements r
                                  WHERE r.tenant_id = prior.tenant_id AND r.job_id = prior.id)
                      AND NOT EXISTS (SELECT 1 FROM job_material_requirements r
                                      WHERE r.tenant_id = prior.tenant_id AND r.job_id = prior.id
                                        AND r.issued_qty < r.required_qty)
                      AND NOT EXISTS (SELECT 1 FROM job_material_issues m
                                      WHERE m.tenant_id = prior.tenant_id AND m.job_id = prior.id
                                        AND m.issue_type IN ('issue', 'return') AND m.unit_cost IS NULL)
                      AND EXISTS (
                          SELECT 1 FROM job_costing_headers h
                          JOIN job_costing_lines l ON l.costing_id = h.id AND l.tenant_id = h.tenant_id
                          WHERE h.tenant_id = prior.tenant_id AND h.job_id = prior.id
                            AND NOT l.is_estimated AND l.cost_type = 'labour')
                      AND EXISTS (
                          SELECT 1 FROM job_costing_headers h
                          JOIN job_costing_lines l ON l.costing_id = h.id AND l.tenant_id = h.tenant_id
                          WHERE h.tenant_id = prior.tenant_id AND h.job_id = prior.id
                            AND NOT l.is_estimated AND l.cost_type IN ('overhead', 'other'))
                )
                SELECT COUNT(*) AS completed_order_count,
                       COALESCE(SUM(c.quantity), 0) AS total_quantity,
                       COALESCE(SUM(
                           COALESCE((SELECT SUM(CASE
                               WHEN m.issue_type = 'issue' THEN m.quantity * m.unit_cost
                               WHEN m.issue_type = 'return' THEN -m.quantity * m.unit_cost
                               ELSE 0 END)
                               FROM job_material_issues m
                               WHERE m.tenant_id = c.tenant_id AND m.job_id = c.id
                                 AND m.issue_type IN ('issue', 'return')), 0)
                           + COALESCE((SELECT SUM(l.amount)
                               FROM job_costing_headers h
                               JOIN job_costing_lines l ON l.costing_id = h.id AND l.tenant_id = h.tenant_id
                               WHERE h.tenant_id = c.tenant_id AND h.job_id = c.id
                                 AND NOT l.is_estimated AND l.cost_type = 'labour'), 0)
                           + COALESCE((SELECT SUM(l.amount)
                               FROM job_costing_headers h
                               JOIN job_costing_lines l ON l.costing_id = h.id AND l.tenant_id = h.tenant_id
                               WHERE h.tenant_id = c.tenant_id AND h.job_id = c.id
                                 AND NOT l.is_estimated AND l.cost_type IN ('overhead', 'other')), 0)
                       ), 0) AS total_actual_cost
                FROM candidates c
                """, rs -> {
            if (!rs.next()) {
                throw new IllegalStateException("Historical costing aggregate returned no row");
            }
            int count = rs.getInt("completed_order_count");
            BigDecimal quantity = rs.getBigDecimal("total_quantity");
            BigDecimal totalCost = JobCostCalculator.money(rs.getBigDecimal("total_actual_cost"));
            BigDecimal averageUnitCost = count == 0 || quantity.signum() == 0
                    ? null : totalCost.divide(quantity, 4, RoundingMode.HALF_UP);
            return new HistoricalCostReference(count, quantity, totalCost, averageUnitCost, HISTORICAL_BASIS);
        }, jobId, tenantId);
    }

    private UUID costingId(UUID tenantId, UUID jobId) {
        return jdbc.queryForObject("""
                SELECT id FROM job_costing_headers WHERE tenant_id = ? AND job_id = ?
                """, UUID.class, tenantId, jobId);
    }

    private void requireJob(UUID tenantId, UUID jobId) {
        String status = jdbc.query("""
                SELECT status FROM production_jobs
                WHERE tenant_id = ? AND id = ? FOR UPDATE
                """, rs -> rs.next() ? rs.getString("status") : null, tenantId, jobId);
        if (status == null || "completed".equals(status) || "cancelled".equals(status)) {
            throw ProductionSupport.conflict("Cost can be recorded only on an open production job");
        }
    }

    private record CostData(
            String jobNumber,
            String jobStatus,
            BigDecimal sellingPrice,
            int estimatedMaterialCount,
            int estimatedLabourCount,
            int estimatedMaterialMissingCostCount,
            int estimatedOverheadCount,
            BigDecimal estimatedMaterial,
            BigDecimal estimatedLabour,
            BigDecimal estimatedOverhead,
            int requirementCount,
            int unissuedRequirementCount,
            int movementCount,
            int unpricedMovementCount,
            BigDecimal actualMaterial,
            int actualLabourCount,
            BigDecimal actualLabour,
            int actualOverheadCount,
            BigDecimal actualOverhead
    ) {}

    record JobCostSummary(CostSummary estimatedCost, CostSummary actualCost, BigDecimal variance) {}
}
