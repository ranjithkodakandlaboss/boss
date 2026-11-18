package in.boss.production.domain;

import in.boss.production.api.ProductionApiDtos.AddCostRequest;
import in.boss.production.api.ProductionApiDtos.CostEntryResponse;
import in.boss.production.api.ProductionApiDtos.CostSummary;
import in.boss.production.api.ProductionApiDtos.CostingResponse;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static in.boss.shared.security.BossPermissions.COMMERCIAL_DATA_READ;

@Service
@Transactional
public class JobCostingService {

    private final JdbcTemplate jdbc;
    private final ProductionSupport support;
    private final ProductionFeatureFlags flags;
    private final ProductionMetrics metrics;
    private final AuditService audit;

    public JobCostingService(JdbcTemplate jdbc, ProductionSupport support, ProductionFeatureFlags flags,
                             ProductionMetrics metrics, AuditService audit) {
        this.jdbc = jdbc;
        this.support = support;
        this.flags = flags;
        this.metrics = metrics;
        this.audit = audit;
    }

    @PreAuthorize(COMMERCIAL_DATA_READ)
    public CostingResponse get(UUID jobId) {
        BossContext context = support.requireContext();
        support.denyForeignJob(jdbc, jobId);
        flags.require(context.tenantId(), ProductionFeatureFlags.JOB_COSTING);
        return load(context.tenantId(), jobId);
    }

    @PreAuthorize(COMMERCIAL_DATA_READ)
    public CostEntryResponse addEntry(UUID jobId, AddCostRequest request) {
        BossContext context = support.requireContext();
        support.denyForeignJob(jdbc, jobId);
        flags.require(context.tenantId(), ProductionFeatureFlags.JOB_COSTING);
        requireJob(context.tenantId(), jobId);
        UUID costingId = costingId(context.tenantId(), jobId);
        String costType = request.entryType().toLowerCase();
        UUID lineId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO job_costing_lines
                  (id, tenant_id, costing_id, cost_type, description, quantity, unit_cost, amount, is_estimated)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE)
                """, lineId, context.tenantId(), costingId, costType, request.description().trim(),
                request.qty(), request.unitCost(), JobCostCalculator.money(request.amount()));
        refresh(context.tenantId(), jobId);
        audit.record("job_costing_lines", lineId, "CREATE", null,
                "{\"entryType\":\"" + request.entryType() + "\"}");
        return new CostEntryResponse(lineId, request.entryType(), request.description().trim(),
                request.qty(), request.unitCost(), JobCostCalculator.money(request.amount()), false, Instant.now());
    }

    public void refresh(UUID tenantId, UUID jobId) {
        BigDecimal material = sum("""
                SELECT COALESCE(SUM(CASE
                    WHEN issue_type = 'issue' THEN quantity * COALESCE(unit_cost, 0)
                    WHEN issue_type = 'return' THEN -quantity * COALESCE(unit_cost, 0)
                    ELSE 0 END), 0)
                FROM job_material_issues WHERE tenant_id = ? AND job_id = ?
                """, tenantId, jobId);
        BigDecimal labour = sum("""
                SELECT COALESCE(SUM(l.amount), 0)
                FROM job_costing_lines l
                JOIN job_costing_headers h ON h.id = l.costing_id AND h.tenant_id = l.tenant_id
                WHERE h.tenant_id = ? AND h.job_id = ? AND l.cost_type = 'labour' AND l.is_estimated = FALSE
                """, tenantId, jobId);
        BigDecimal overhead = sum("""
                SELECT COALESCE(SUM(l.amount), 0)
                FROM job_costing_lines l
                JOIN job_costing_headers h ON h.id = l.costing_id AND h.tenant_id = l.tenant_id
                WHERE h.tenant_id = ? AND h.job_id = ? AND l.cost_type IN ('overhead', 'other')
                  AND l.is_estimated = FALSE
                """, tenantId, jobId);
        BigDecimal selling = jdbc.queryForObject("""
                SELECT selling_price FROM job_costing_headers WHERE tenant_id = ? AND job_id = ?
                """, BigDecimal.class, tenantId, jobId);
        BigDecimal actual = JobCostCalculator.total(material, labour, overhead);
        BigDecimal margin = JobCostCalculator.marginPct(selling, actual);
        jdbc.update("""
                UPDATE job_costing_headers
                SET act_material_cost = ?, act_labour_cost = ?, act_overhead_cost = ?,
                    actual_margin_pct = ?, status = 'in_progress', updated_at = NOW()
                WHERE tenant_id = ? AND job_id = ?
                """, JobCostCalculator.money(material), JobCostCalculator.money(labour),
                JobCostCalculator.money(overhead), margin, tenantId, jobId);
        BigDecimal estimated = sum("""
                SELECT est_material_cost + est_labour_cost + est_overhead_cost
                FROM job_costing_headers WHERE tenant_id = ? AND job_id = ?
                """, tenantId, jobId);
        metrics.afterCommit(() -> {
            metrics.costing("success");
            metrics.variance(JobCostCalculator.variance(actual, estimated));
        });
    }

    public void close(UUID tenantId, UUID jobId) {
        refresh(tenantId, jobId);
        jdbc.update("""
                UPDATE job_costing_headers SET status = 'actual', updated_at = NOW()
                WHERE tenant_id = ? AND job_id = ?
                """, tenantId, jobId);
    }

    private CostingResponse load(UUID tenantId, UUID jobId) {
        record Header(String number, BigDecimal estM, BigDecimal estL, BigDecimal estO,
                      BigDecimal actM, BigDecimal actL, BigDecimal actO, BigDecimal selling) {}
        Header header = jdbc.query("""
                SELECT j.job_number, h.est_material_cost, h.est_labour_cost, h.est_overhead_cost,
                       h.act_material_cost, h.act_labour_cost, h.act_overhead_cost, h.selling_price
                FROM job_costing_headers h
                JOIN production_jobs j ON j.id = h.job_id AND j.tenant_id = h.tenant_id
                WHERE h.tenant_id = ? AND h.job_id = ?
                """, rs -> {
            if (!rs.next()) {
                throw ProductionSupport.notFound("Job costing not found");
            }
            return new Header(rs.getString("job_number"),
                    rs.getBigDecimal("est_material_cost"), rs.getBigDecimal("est_labour_cost"),
                    rs.getBigDecimal("est_overhead_cost"), rs.getBigDecimal("act_material_cost"),
                    rs.getBigDecimal("act_labour_cost"), rs.getBigDecimal("act_overhead_cost"),
                    rs.getBigDecimal("selling_price"));
        }, tenantId, jobId);
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
        CostSummary estimated = summary(header.estM, header.estL, header.estO, null);
        CostSummary actual = summary(header.actM, header.actL, header.actO, header.selling);
        return new CostingResponse(jobId, header.number, estimated, actual,
                JobCostCalculator.variance(actual.totalCost(), estimated.totalCost()), entries);
    }

    private static CostSummary summary(BigDecimal material, BigDecimal labour, BigDecimal overhead, BigDecimal selling) {
        BigDecimal total = JobCostCalculator.total(material, labour, overhead);
        return new CostSummary(JobCostCalculator.money(material), JobCostCalculator.money(labour),
                JobCostCalculator.money(overhead), total, selling, JobCostCalculator.marginPct(selling, total));
    }

    private UUID costingId(UUID tenantId, UUID jobId) {
        return jdbc.queryForObject("""
                SELECT id FROM job_costing_headers WHERE tenant_id = ? AND job_id = ?
                """, UUID.class, tenantId, jobId);
    }

    private void requireJob(UUID tenantId, UUID jobId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM production_jobs
                WHERE tenant_id = ? AND id = ? AND status NOT IN ('completed', 'cancelled')
                """, Integer.class, tenantId, jobId);
        if (count == null || count == 0) {
            throw ProductionSupport.conflict("Cost can be recorded only on an open production job");
        }
    }

    private BigDecimal sum(String sql, UUID tenantId, UUID jobId) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, tenantId, jobId);
        return value == null ? BigDecimal.ZERO : value;
    }
}
