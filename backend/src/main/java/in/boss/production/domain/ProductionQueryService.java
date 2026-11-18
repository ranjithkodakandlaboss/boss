package in.boss.production.domain;

import in.boss.production.api.ProductionApiDtos.BusinessMetrics;
import in.boss.production.api.ProductionApiDtos.EligibleCpoLine;
import in.boss.production.api.ProductionApiDtos.PageResponse;
import in.boss.production.api.ProductionApiDtos.ProductionJobResponse;
import in.boss.production.api.ProductionApiDtos.RoutingTemplateOption;
import in.boss.shared.context.BossContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ProductionQueryService {

    private static final int MAX_PAGE = 200;

    private final JdbcTemplate jdbc;
    private final ProductionSupport support;
    private final ProductionFeatureFlags flags;
    private final ProductionJobView jobs;

    public ProductionQueryService(JdbcTemplate jdbc, ProductionSupport support, ProductionFeatureFlags flags,
                                  ProductionJobView jobs) {
        this.jdbc = jdbc;
        this.support = support;
        this.flags = flags;
        this.jobs = jobs;
    }

    public PageResponse<ProductionJobResponse> list(UUID cpoId, String status, int page, int size) {
        BossContext context = support.requireContext();
        checkPage(page, size);
        List<Object> args = new ArrayList<>();
        String filter = filters(context.tenantId(), cpoId, status, args);
        args.add(size);
        args.add(page * size);
        List<ProductionJobResponse> content = jdbc.query(selectSql() + filter
                + " ORDER BY j.created_at DESC LIMIT ? OFFSET ?", jobs::map, args.toArray());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM production_jobs j WHERE j.tenant_id = ?"
                + statusClause(status) + cpoClause(cpoId), Long.class, countArgs(context.tenantId(), cpoId, status));
        return new PageResponse<>(content, page, size, total == null ? 0 : total);
    }

    public ProductionJobResponse get(UUID jobId) {
        BossContext context = support.requireContext();
        List<ProductionJobResponse> rows = jdbc.query(selectSql() + " AND j.id = ?",
                jobs::map, context.tenantId(), jobId);
        if (rows.isEmpty()) {
            throw ProductionSupport.notFound("Production job not found");
        }
        return rows.get(0);
    }

    public List<EligibleCpoLine> eligibleLines() {
        BossContext context = support.requireContext();
        return jdbc.query("""
                SELECT c.id AS cpo_id, c.cpo_number, l.id AS line_id, c.customer_id, cu.name AS customer_name,
                       l.item_id, i.code AS item_code, i.name AS item_name, l.quantity, l.unit_price,
                       c.site_id, s.name AS site_name, c.required_by_date, c.status
                FROM cpos c
                JOIN cpo_lines l ON l.cpo_id = c.id AND l.tenant_id = c.tenant_id
                JOIN customers cu ON cu.id = c.customer_id AND cu.tenant_id = c.tenant_id
                JOIN items i ON i.id = l.item_id AND i.tenant_id = c.tenant_id
                JOIN physical_sites s ON s.id = c.site_id AND s.tenant_id = c.tenant_id
                WHERE c.tenant_id = ?
                  AND c.status IN ('open', 'partial')
                  AND NOT EXISTS (
                    SELECT 1 FROM production_jobs j
                    WHERE j.tenant_id = c.tenant_id AND j.cpo_line_id = l.id AND j.status <> 'cancelled')
                ORDER BY c.created_at DESC, l.line_no
                """, (rs, row) -> new EligibleCpoLine(
                rs.getObject("cpo_id", UUID.class),
                rs.getString("cpo_number"),
                rs.getObject("line_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("customer_name"),
                rs.getObject("item_id", UUID.class),
                rs.getString("item_code"),
                rs.getString("item_name"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_price"),
                selling(rs.getBigDecimal("unit_price"), rs.getBigDecimal("quantity")),
                rs.getObject("site_id", UUID.class),
                rs.getString("site_name"),
                date(rs, "required_by_date"),
                rs.getString("status")), context.tenantId());
    }

    public List<RoutingTemplateOption> routingTemplates(UUID itemId) {
        BossContext context = support.requireContext();
        String itemFilter = itemId == null
                ? ""
                : " AND (t.finished_item_id IS NULL OR t.finished_item_id = ?)";
        Object[] args = itemId == null
                ? new Object[]{context.tenantId()}
                : new Object[]{context.tenantId(), itemId};
        return jdbc.query("""
                SELECT t.id, t.name, t.version, t.finished_item_id,
                       (SELECT COUNT(*) FROM routing_template_stages s
                         WHERE s.tenant_id = t.tenant_id AND s.routing_id = t.id) AS stage_count
                FROM routing_templates t
                WHERE t.tenant_id = ? AND t.status = 'active'
                """ + itemFilter + " ORDER BY t.name", (rs, row) -> new RoutingTemplateOption(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("version"),
                rs.getObject("finished_item_id", UUID.class),
                rs.getInt("stage_count")), args);
    }

    public Map<String, Boolean> features() {
        return flags.snapshot(support.requireContext().tenantId());
    }

    public BusinessMetrics businessMetrics() {
        BossContext context = support.requireContext();
        UUID tenantId = context.tenantId();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbc.query("""
                SELECT status, COUNT(*) AS n FROM production_jobs
                WHERE tenant_id = ? GROUP BY status
                """, rs -> {
            byStatus.put(rs.getString("status"), rs.getLong("n"));
        }, tenantId);
        Map<String, Long> byStage = new LinkedHashMap<>();
        jdbc.query("""
                SELECT s.stage_label_snapshot AS stage_name, COUNT(*) AS n
                FROM job_stages s
                JOIN production_jobs j ON j.id = s.job_id AND j.tenant_id = s.tenant_id
                WHERE s.tenant_id = ? AND s.status = 'in_progress'
                  AND j.status NOT IN ('completed', 'cancelled')
                GROUP BY s.stage_label_snapshot
                """, rs -> {
            byStage.put(rs.getString("stage_name"), rs.getLong("n"));
        }, tenantId);
        BigDecimal issued = sum("""
                SELECT COALESCE(SUM(quantity), 0) FROM job_material_issues
                WHERE tenant_id = ? AND issue_type = 'issue'
                """, tenantId);
        BigDecimal returned = sum("""
                SELECT COALESCE(SUM(quantity), 0) FROM job_material_issues
                WHERE tenant_id = ? AND issue_type = 'return'
                """, tenantId);
        BigDecimal estimated = sum("""
                SELECT COALESCE(SUM(est_material_cost + est_labour_cost + est_overhead_cost), 0)
                FROM job_costing_headers WHERE tenant_id = ?
                """, tenantId);
        BigDecimal actual = sum("""
                SELECT COALESCE(SUM(act_material_cost + act_labour_cost + act_overhead_cost), 0)
                FROM job_costing_headers WHERE tenant_id = ?
                """, tenantId);
        long active = count("""
                SELECT COUNT(*) FROM production_jobs
                WHERE tenant_id = ? AND status IN ('planned','released','in_progress','on_hold')
                """, tenantId);
        long completed = count("""
                SELECT COUNT(*) FROM production_jobs WHERE tenant_id = ? AND status = 'completed'
                """, tenantId);
        long delayed = count("""
                SELECT COUNT(*) FROM production_jobs
                WHERE tenant_id = ? AND status NOT IN ('completed','cancelled')
                  AND planned_end IS NOT NULL AND planned_end < CURRENT_DATE
                """, tenantId);
        boolean showMoney = support.commercial(context);
        return new BusinessMetrics(active, completed, delayed, byStatus, byStage,
                issued, returned, issued.subtract(returned),
                showMoney ? estimated : null,
                showMoney ? actual : null,
                showMoney ? JobCostCalculator.variance(actual, estimated) : null);
    }

    private String selectSql() {
        return """
                SELECT j.tenant_id, j.id, j.job_number, j.cpo_id, j.cpo_line_id, j.finished_item_id,
                       j.routing_id, j.routing_version, j.site_id, j.quantity, j.status, j.priority,
                       j.planned_start, j.planned_end, j.actual_start, j.actual_end, j.selling_price,
                       j.created_at, c.cpo_number, c.customer_id, cu.name AS customer_name,
                       i.code AS item_code, i.name AS item_name, u.symbol AS uom_symbol,
                       rt.name AS routing_name, s.name AS site_name,
                       h.est_material_cost, h.est_labour_cost, h.est_overhead_cost,
                       h.act_material_cost, h.act_labour_cost, h.act_overhead_cost
                FROM production_jobs j
                LEFT JOIN cpos c ON c.id = j.cpo_id AND c.tenant_id = j.tenant_id
                LEFT JOIN customers cu ON cu.id = c.customer_id AND cu.tenant_id = j.tenant_id
                JOIN items i ON i.id = j.finished_item_id AND i.tenant_id = j.tenant_id
                JOIN units_of_measure u ON u.id = j.uom_id
                LEFT JOIN routing_templates rt ON rt.id = j.routing_id AND rt.tenant_id = j.tenant_id
                JOIN physical_sites s ON s.id = j.site_id AND s.tenant_id = j.tenant_id
                LEFT JOIN job_costing_headers h ON h.job_id = j.id AND h.tenant_id = j.tenant_id
                WHERE j.tenant_id = ?
                """;
    }

    private static String filters(UUID tenantId, UUID cpoId, String status, List<Object> args) {
        args.add(tenantId);
        StringBuilder sql = new StringBuilder();
        if (status != null && !status.isBlank()) {
            sql.append(" AND j.status = ?");
            args.add(status);
        }
        if (cpoId != null) {
            sql.append(" AND j.cpo_id = ?");
            args.add(cpoId);
        }
        return sql.toString();
    }

    private static String statusClause(String status) {
        return status == null || status.isBlank() ? "" : " AND j.status = ?";
    }

    private static String cpoClause(UUID cpoId) {
        return cpoId == null ? "" : " AND j.cpo_id = ?";
    }

    private static Object[] countArgs(UUID tenantId, UUID cpoId, String status) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (status != null && !status.isBlank()) {
            args.add(status);
        }
        if (cpoId != null) {
            args.add(cpoId);
        }
        return args.toArray();
    }

    private BigDecimal sum(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }

    private long count(String sql, UUID tenantId) {
        Long value = jdbc.queryForObject(sql, Long.class, tenantId);
        return value == null ? 0 : value;
    }

    private static BigDecimal selling(BigDecimal unitPrice, BigDecimal qty) {
        if (unitPrice == null || qty == null) {
            return null;
        }
        return JobCostCalculator.money(unitPrice.multiply(qty));
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static void checkPage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE) {
            throw ProductionSupport.badRequest("Page size must be between 1 and " + MAX_PAGE);
        }
    }
}
