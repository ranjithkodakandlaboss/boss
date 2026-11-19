package in.boss.production.domain;

import in.boss.inventory.domain.InventoryService;
import in.boss.masterdata.domain.BomExplosionService;
import in.boss.production.api.ProductionApiDtos.CreateJobRequest;
import in.boss.production.api.ProductionApiDtos.RoutingStageRequest;
import in.boss.production.api.ProductionApiDtos.CreateRoutingTemplateRequest;
import in.boss.production.api.ProductionApiDtos.ReviseRoutingTemplateRequest;
import in.boss.production.api.ProductionApiDtos.RoutingTemplateOption;
import in.boss.production.api.ProductionApiDtos.ProductionJobResponse;
import in.boss.production.api.ProductionApiDtos.ReasonRequest;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.outbox.OutboxPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static in.boss.shared.security.BossPermissions.OWNER_OR_OPS;
import static in.boss.shared.security.BossPermissions.PRODUCTION_WRITE;

@Service
@Transactional
public class ProductionJobService {

    private final JdbcTemplate jdbc;
    private final ProductionSupport support;
    private final ProductionFeatureFlags flags;
    private final ProductionMetrics metrics;
    private final ProductionQueryService query;
    private final BomExplosionService explosion;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final InventoryService inventory;

    public ProductionJobService(JdbcTemplate jdbc, ProductionSupport support, ProductionFeatureFlags flags,
                                ProductionMetrics metrics, ProductionQueryService query,
                                BomExplosionService explosion, AuditService audit, OutboxPublisher outbox,
                                InventoryService inventory) {
        this.jdbc = jdbc;
        this.support = support;
        this.flags = flags;
        this.metrics = metrics;
        this.query = query;
        this.explosion = explosion;
        this.audit = audit;
        this.outbox = outbox;
        this.inventory = inventory;
    }

    @PreAuthorize(OWNER_OR_OPS)
    public RoutingTemplateOption createRoutingTemplate(CreateRoutingTemplateRequest request) {
        BossContext context = support.requireContext();
        String name = request.name().trim();
        List<RoutingStageRequest> requested = request.stages() == null || request.stages().isEmpty()
                ? List.of(new RoutingStageRequest(
                request.stageName() == null ? "" : request.stageName(), null, null, null))
                : request.stages();
        if (requested.stream().anyMatch(stage -> stage.name() == null || stage.name().isBlank())) {
            throw ProductionSupport.badRequest("Every workflow stage needs a name");
        }
        Integer existing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM routing_templates
                WHERE tenant_id = ? AND status = 'active' AND lower(name) = lower(?)
                """, Integer.class, context.tenantId(), name);
        if (existing != null && existing > 0) {
            throw ProductionSupport.conflict("A workflow with this name already exists");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO routing_templates (id, tenant_id, name, version, is_default, status)
                VALUES (?, ?, ?, 'v1', false, 'active')
                """, id, context.tenantId(), name);
        saveRoutingStages(context.tenantId(), id, requested);
        audit.record("routing_templates", id, "CREATE", null,
                "{\"name\":\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        return new RoutingTemplateOption(id, name, "v1", null, requested.size());
    }

    @PreAuthorize(OWNER_OR_OPS)
    public RoutingTemplateOption reviseRoutingTemplate(UUID routingId, ReviseRoutingTemplateRequest request) {
        BossContext context = support.requireContext();
        List<RoutingStageRequest> requested = request == null ? null : request.stages();
        validateRoutingStages(requested);
        RoutingRevision current = jdbc.query("""
                SELECT id, name, version, finished_item_id, is_default, status,
                       GREATEST(revision_number, CASE
                         WHEN version ~ '^v[1-9][0-9]*$' THEN substring(version FROM 2)::INTEGER
                         ELSE 1
                       END) AS revision_number
                FROM routing_templates
                WHERE tenant_id = ? AND id = ?
                FOR UPDATE
                """, rs -> rs.next() ? new RoutingRevision(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("version"),
                rs.getObject("finished_item_id", UUID.class), rs.getBoolean("is_default"),
                rs.getString("status"), rs.getInt("revision_number")) : null,
                context.tenantId(), routingId);
        if (current == null || !"active".equals(current.status())) {
            throw ProductionSupport.notFound("Active routing template was not found for this tenant");
        }

        UUID revisedId = UUID.randomUUID();
        int revisionNumber = current.revisionNumber() + 1;
        String version = "v" + revisionNumber;
        jdbc.update("""
                UPDATE routing_templates
                SET status = 'archived', is_default = FALSE, revision_number = ?, updated_at = NOW()
                WHERE tenant_id = ? AND id = ? AND status = 'active'
                """, current.revisionNumber(), context.tenantId(), current.id());
        jdbc.update("""
                INSERT INTO routing_templates
                  (id, tenant_id, finished_item_id, name, version, is_default, status,
                   revision_number, supersedes_id)
                VALUES (?, ?, ?, ?, ?, ?, 'active', ?, ?)
                """, revisedId, context.tenantId(), current.finishedItemId(), current.name(),
                version, current.isDefault(), revisionNumber, current.id());
        saveRoutingStages(context.tenantId(), revisedId, requested);
        audit.record("routing_templates", revisedId, "REVISION",
                "{\"routingTemplateId\":\"" + current.id() + "\",\"version\":\"" + current.version() + "\"}",
                "{\"version\":\"" + version + "\"}");
        return new RoutingTemplateOption(revisedId, current.name(), version,
                current.finishedItemId(), requested.size());
    }

    @PreAuthorize(PRODUCTION_WRITE)
    public ProductionJobResponse create(CreateJobRequest request) {
        BossContext context = support.requireContext();
        flags.require(context.tenantId(), ProductionFeatureFlags.JOB_CREATE);
        flags.require(context.tenantId(), ProductionFeatureFlags.ROUTING_STAGES);
        try {
            ProductionJobResponse created = insertJob(context, request);
            metrics.afterCommit(() -> metrics.jobCreation("success"));
            return created;
        } catch (RuntimeException ex) {
            metrics.jobCreation("failure");
            throw ex;
        }
    }

    @PreAuthorize(PRODUCTION_WRITE)
    public ProductionJobResponse hold(UUID jobId, ReasonRequest request) {
        return changeStatus(jobId, JobStatusMachine.ON_HOLD, request.reason(), true);
    }

    @PreAuthorize(PRODUCTION_WRITE)
    public ProductionJobResponse releaseHold(UUID jobId) {
        return changeStatus(jobId, JobStatusMachine.IN_PROGRESS, null, true);
    }

    @PreAuthorize(OWNER_OR_OPS)
    public ProductionJobResponse cancel(UUID jobId, ReasonRequest request) {
        return changeStatus(jobId, JobStatusMachine.CANCELLED, request.reason(), false);
    }

    private ProductionJobResponse insertJob(BossContext context, CreateJobRequest request) {
        CpoLine line = lockLine(context, request);
        BigDecimal quantity = jobQuantity(context.tenantId(), line, request.quantity());
        RoutingTemplate routing = requireRouting(context.tenantId(), request.routingTemplateId(), line.itemId());
        List<StageSeed> stages = loadStages(context.tenantId(), routing.id());
        if (stages.isEmpty()) {
            throw ProductionSupport.badRequest("Routing template has no stages");
        }
        for (StageSeed stage : stages) {
            requireAssetCapability(context.tenantId(), stage.assetId(), stage.configId());
        }
        UUID jobId = UUID.randomUUID();
        String jobNumber = "JOB-" + LocalDate.now() + "-" + jobId.toString().substring(0, 8).toUpperCase();
        BigDecimal selling = JobCostCalculator.money(line.unitPrice().multiply(quantity));
        LocalDate plannedEnd = request.scheduledEnd() != null ? request.scheduledEnd() : line.requiredBy();
        String priority = request.priority() == null ? "normal" : request.priority();
        jdbc.update("""
                INSERT INTO production_jobs
                  (id, tenant_id, job_number, cpo_id, cpo_line_id, finished_item_id, routing_id,
                   routing_version, site_id, quantity, uom_id, status, planned_start, planned_end,
                   priority, selling_price, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'planned', ?, ?, ?, ?, ?)
                """, jobId, context.tenantId(), jobNumber, line.cpoId(), line.lineId(), line.itemId(),
                routing.id(), routing.version(), line.siteId(), quantity, line.uomId(),
                request.scheduledStart(), plannedEnd, priority, selling, context.userId());
        insertStages(context.tenantId(), jobId, stages);
        MaterialEstimate estimated = insertMaterials(context, jobId, line, quantity);
        insertCostHeader(context.tenantId(), jobId, estimated, selling);
        audit.record("production_job", jobId, "CREATE", null, "{\"status\":\"planned\"}");
        outbox.publish("production_job", jobId, "production.job.created",
                "{\"jobId\":\"" + jobId + "\",\"status\":\"planned\"}");
        return query.get(jobId);
    }

    private CpoLine lockLine(BossContext context, CreateJobRequest request) {
        CpoLine line = jdbc.query("""
                SELECT c.id AS cpo_id, c.status, c.site_id, c.required_by_date,
                       l.id AS line_id, l.item_id, l.quantity, l.uom_id, l.unit_price
                FROM cpos c
                JOIN cpo_lines l ON l.cpo_id = c.id AND l.tenant_id = c.tenant_id
                WHERE c.tenant_id = ? AND c.id = ? AND l.id = ?
                FOR UPDATE OF c
                """, rs -> {
            if (!rs.next()) {
                return null;
            }
            return new CpoLine(rs.getObject("cpo_id", UUID.class), rs.getString("status"),
                    rs.getObject("site_id", UUID.class),
                    rs.getDate("required_by_date") == null ? null : rs.getDate("required_by_date").toLocalDate(),
                    rs.getObject("line_id", UUID.class), rs.getObject("item_id", UUID.class),
                    rs.getBigDecimal("quantity"), rs.getObject("uom_id", UUID.class),
                    rs.getBigDecimal("unit_price"));
        }, context.tenantId(), request.cpoId(), request.cpoLineId());
        if (line == null) {
            throw ProductionSupport.badRequest("Customer PO line was not found for this tenant");
        }
        if (!context.canAccessSite(line.siteId())) {
            throw ProductionSupport.notFound("Customer PO line was not found for this tenant");
        }
        if (!Set.of("open", "partial", "feasibility_approved").contains(line.status())) {
            throw ProductionSupport.badRequest("Customer PO status " + line.status() + " cannot start production");
        }
        return line;
    }

    private BigDecimal jobQuantity(UUID tenantId, CpoLine line, BigDecimal requested) {
        BigDecimal allocated = jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM production_jobs
                WHERE tenant_id = ? AND cpo_line_id = ? AND status <> 'cancelled'
                """, BigDecimal.class, tenantId, line.lineId());
        BigDecimal used = allocated == null ? BigDecimal.ZERO : allocated;
        BigDecimal remaining = line.quantity().subtract(used);
        if (remaining.signum() <= 0) {
            throw ProductionSupport.conflict("A production job already exists for this Customer PO line");
        }
        BigDecimal quantity = requested == null ? remaining : requested;
        if (quantity.signum() <= 0 || quantity.compareTo(remaining) > 0) {
            throw ProductionSupport.badRequest("Job quantity exceeds the remaining unallocated Customer PO line quantity");
        }
        return quantity;
    }

    private RoutingTemplate requireRouting(UUID tenantId, UUID routingId, UUID itemId) {
        RoutingTemplate routing = jdbc.query("""
                SELECT id, version, status, finished_item_id
                FROM routing_templates WHERE tenant_id = ? AND id = ?
                """, rs -> rs.next() ? new RoutingTemplate(rs.getObject("id", UUID.class),
                rs.getString("version"), rs.getString("status"),
                rs.getObject("finished_item_id", UUID.class)) : null, tenantId, routingId);
        if (routing == null || !"active".equals(routing.status())) {
            throw ProductionSupport.badRequest("Active routing template was not found");
        }
        if (routing.itemId() != null && !routing.itemId().equals(itemId)) {
            throw ProductionSupport.badRequest("Routing template does not match the Customer PO item");
        }
        return routing;
    }

    private List<StageSeed> loadStages(UUID tenantId, UUID routingId) {
        Integer versioned = jdbc.queryForObject("""
                SELECT COUNT(*) FROM routing_template_stages WHERE tenant_id = ? AND routing_id = ?
                """, Integer.class, tenantId, routingId);
        if (versioned != null && versioned > 0) {
            return jdbc.query("""
                    SELECT s.seq_no, s.name, s.requires_inspection, s.inspection_template_id, s.is_optional,
                           s.stage_config_id, s.work_centre, s.asset_id, o.code, o.label
                    FROM routing_template_stages s
                    LEFT JOIN tenant_config_options o
                      ON o.id = s.stage_config_id AND o.tenant_id = s.tenant_id
                    WHERE s.tenant_id = ? AND s.routing_id = ?
                    ORDER BY s.seq_no
                    """, (rs, row) -> {
                String label = rs.getString("label");
                String name = label == null || label.isBlank() ? rs.getString("name") : label;
                return new StageSeed(rs.getInt("seq_no"), name, rs.getString("code"),
                        rs.getObject("stage_config_id", UUID.class), rs.getBoolean("requires_inspection"),
                        rs.getObject("inspection_template_id", UUID.class), rs.getBoolean("is_optional"),
                        rs.getString("work_centre"), rs.getObject("asset_id", UUID.class));
            }, tenantId, routingId);
        }
        return jdbc.query("""
                SELECT seq_no, name, work_centre, requires_inspection, is_optional
                FROM routing_operations WHERE tenant_id = ? AND routing_id = ? ORDER BY seq_no
                """, (rs, row) -> new StageSeed(rs.getInt("seq_no"), rs.getString("name"), null, null,
                rs.getBoolean("requires_inspection"), null, rs.getBoolean("is_optional"),
                rs.getString("work_centre"), null), tenantId, routingId);
    }

    private void insertStages(UUID tenantId, UUID jobId, List<StageSeed> stages) {
        for (StageSeed stage : stages) {
            jdbc.update("""
                    INSERT INTO job_stages
                      (id, tenant_id, job_id, seq_no, name, stage_code, stage_config_option_id,
                       stage_label_snapshot, status, requires_inspection, inspection_template_id,
                       is_optional, work_centre, asset_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), tenantId, jobId, stage.seq(), stage.name(), stage.code(),
                    stage.configId(), stage.name(), stage.inspection(), stage.templateId(),
                    stage.optional(), stage.workCentre(), stage.assetId());
        }
    }

    private MaterialEstimate insertMaterials(BossContext context, UUID jobId, CpoLine line, BigDecimal quantity) {
        var exploded = explosion.explodeBOM(line.itemId(), quantity);
        if (exploded.getFlatMaterials() == null || exploded.getFlatMaterials().isEmpty()) {
            throw ProductionSupport.badRequest("An approved BOM is required to create a production job");
        }
        BigDecimal estimated = BigDecimal.ZERO;
        boolean complete = true;
        for (var material : exploded.getFlatMaterials()) {
            ItemCost item = jdbc.query("""
                    SELECT base_uom_id, std_cost
                    FROM items WHERE tenant_id = ? AND id = ?
                    """, rs -> rs.next() ? new ItemCost(rs.getObject("base_uom_id", UUID.class),
                    rs.getBigDecimal("std_cost")) : null, context.tenantId(), material.getItemId());
            if (item == null) {
                throw ProductionSupport.badRequest("BOM component was not found");
            }
            BigDecimal qty = material.getQty().setScale(3, java.math.RoundingMode.HALF_UP);
            if (qty.signum() <= 0) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO job_material_requirements
                      (id, tenant_id, job_id, item_id, required_qty, uom_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), context.tenantId(), jobId, material.getItemId(), qty, item.uomId());
            if (item.stdCost() == null) {
                complete = false;
            } else {
                estimated = estimated.add(qty.multiply(item.stdCost()));
            }
        }
        if (estimated.signum() == 0 && exploded.getFlatMaterials().isEmpty()) {
            throw ProductionSupport.badRequest("An approved BOM is required to create a production job");
        }
        return new MaterialEstimate(JobCostCalculator.money(estimated), complete);
    }

    private void insertCostHeader(UUID tenantId, UUID jobId, MaterialEstimate estimated, BigDecimal selling) {
        UUID costingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO job_costing_headers
                  (id, tenant_id, job_id, est_material_cost, selling_price, status)
                VALUES (?, ?, ?, ?, ?, 'estimated')
                """, costingId, tenantId, jobId, estimated.materialCost(), selling);
        jdbc.update("""
                INSERT INTO job_costing_lines
                  (id, tenant_id, costing_id, cost_type, description, quantity, unit_cost, amount, is_estimated)
                VALUES (?, ?, ?, 'material', 'Estimated BOM material; item standard cost snapshot', 1, ?, ?, TRUE)
                """, UUID.randomUUID(), tenantId, costingId,
                estimated.complete() ? estimated.materialCost() : null, estimated.materialCost());
    }

    private ProductionJobResponse changeStatus(UUID jobId, String target, String reason, boolean hold) {
        BossContext context = support.requireContext();
        support.denyForeignJob(jdbc, jobId);
        flags.require(context.tenantId(), ProductionFeatureFlags.STAGE_TRANSITION);
        String current = jdbc.query("""
                SELECT status FROM production_jobs WHERE tenant_id = ? AND id = ? FOR UPDATE
                """, rs -> rs.next() ? rs.getString("status") : null, context.tenantId(), jobId);
        if (current == null) {
            throw ProductionSupport.notFound("Production job not found");
        }
        JobStatusMachine.assertTransition(current, target);
        if (hold && JobStatusMachine.ON_HOLD.equals(target)) {
            jdbc.update("""
                    UPDATE production_jobs SET status = ?, hold_reason = ?, updated_at = NOW()
                    WHERE tenant_id = ? AND id = ?
                    """, target, reason.trim(), context.tenantId(), jobId);
        } else if (JobStatusMachine.CANCELLED.equals(target)) {
            inventory.releaseJobReservations(jobId);
            jdbc.update("""
                    UPDATE production_jobs SET status = ?, cancel_reason = ?, updated_at = NOW()
                    WHERE tenant_id = ? AND id = ?
                    """, target, reason.trim(), context.tenantId(), jobId);
        } else {
            jdbc.update("""
                    UPDATE production_jobs
                    SET status = ?, hold_reason = NULL, updated_at = NOW()
                    WHERE tenant_id = ? AND id = ?
                    """, target, context.tenantId(), jobId);
        }
        audit.record("production_job", jobId, "STATUS", "{\"status\":\"" + current + "\"}",
                "{\"status\":\"" + target + "\"}");
        outbox.publish("production_job", jobId, "production.job.status_changed",
                "{\"from\":\"" + current + "\",\"to\":\"" + target + "\"}");
        metrics.afterCommit(() -> metrics.jobTransition(current, target));
        return query.get(jobId);
    }

    private record CpoLine(UUID cpoId, String status, UUID siteId, LocalDate requiredBy, UUID lineId,
                           UUID itemId, BigDecimal quantity, UUID uomId, BigDecimal unitPrice) {}

    private record RoutingTemplate(UUID id, String version, String status, UUID itemId) {}

    private record RoutingRevision(UUID id, String name, String version, UUID finishedItemId,
                                   boolean isDefault, String status, int revisionNumber) {}

    private record StageSeed(int seq, String name, String code, UUID configId, boolean inspection,
                             UUID templateId, boolean optional, String workCentre, UUID assetId) {}

    private record StageOption(UUID id, String label) {}

    private void saveRoutingStages(UUID tenantId, UUID routingId, List<RoutingStageRequest> stages) {
        validateRoutingStages(stages);
        int seq = 1;
        for (RoutingStageRequest stage : stages) {
            StageOption option = resolveStage(tenantId, stage);
            requireAssetCapability(tenantId, stage.assetId(), option.id());
            if (stage.inspectionTemplateId() != null) {
                Integer active = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM inspection_parameter_templates
                        WHERE tenant_id = ? AND id = ? AND is_active = TRUE
                        """, Integer.class, tenantId, stage.inspectionTemplateId());
                if (active == null || active == 0) {
                    throw ProductionSupport.badRequest(
                            "Inspection template was not found or is inactive for this tenant");
                }
            }
            jdbc.update("""
                    INSERT INTO routing_template_stages
                      (tenant_id, routing_id, stage_config_id, seq_no, name, stage_type, work_centre,
                       asset_id, requires_inspection, inspection_template_id)
                    VALUES (?, ?, ?, ?, ?, 'production', ?, ?, ?, ?)
                    """, tenantId, routingId, option.id(), seq++, option.label(), stage.workCentre(),
                    stage.assetId(), stage.inspectionTemplateId() != null, stage.inspectionTemplateId());
        }
    }

    private void validateRoutingStages(List<RoutingStageRequest> stages) {
        if (stages == null || stages.isEmpty()
                || stages.stream().anyMatch(stage -> stage == null || stage.name() == null
                || stage.name().isBlank())) {
            throw ProductionSupport.badRequest("Every routing template needs stages with names");
        }
    }

    private StageOption resolveStage(UUID tenantId, RoutingStageRequest stage) {
        if (stage.stageConfigOptionId() != null) {
            StageOption option = jdbc.query("""
                    SELECT o.id, o.label FROM tenant_config_options o
                    JOIN config_categories c ON c.id = o.category_id
                    WHERE o.tenant_id = ? AND o.id = ? AND c.code = 'PRODUCTION_STAGES' AND o.is_active
                    """, rs -> rs.next() ? new StageOption(rs.getObject("id", UUID.class), rs.getString("label")) : null,
                    tenantId, stage.stageConfigOptionId());
            if (option == null) {
                throw ProductionSupport.badRequest("Production stage is not a configured activity for this tenant");
            }
            return option;
        }
        StageOption matched = jdbc.query("""
                SELECT o.id, o.label FROM tenant_config_options o
                JOIN config_categories c ON c.id = o.category_id
                WHERE o.tenant_id = ? AND c.code = 'PRODUCTION_STAGES' AND o.is_active
                  AND lower(o.label) = lower(?)
                """, rs -> rs.next() ? new StageOption(rs.getObject("id", UUID.class), rs.getString("label")) : null,
                tenantId, stage.name().trim());
        return matched == null ? new StageOption(null, stage.name().trim()) : matched;
    }

    private void requireAssetCapability(UUID tenantId, UUID assetId, UUID stageOptionId) {
        if (assetId == null) {
            return;
        }
        if (stageOptionId == null) {
            throw ProductionSupport.badRequest("Select a configured production stage before assigning an asset");
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM asset_capabilities
                WHERE tenant_id = ? AND asset_id = ? AND stage_option_id = ?
                """, Integer.class, tenantId, assetId, stageOptionId);
        if (count == null || count == 0) {
            throw ProductionSupport.badRequest("The selected asset does not support this production stage");
        }
    }

    private record ItemCost(UUID uomId, BigDecimal stdCost) {}
    private record MaterialEstimate(BigDecimal materialCost, boolean complete) {}
}
