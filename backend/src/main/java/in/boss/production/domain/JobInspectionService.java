package in.boss.production.domain;

import in.boss.production.api.ProductionApiDtos.ConcessionRequest;
import in.boss.production.api.ProductionApiDtos.InspectionResultRequest;
import in.boss.production.api.ProductionApiDtos.ProductionJobResponse;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static in.boss.shared.security.BossPermissions.CONCESSION_APPROVE;
import static in.boss.shared.security.BossPermissions.QUALITY_WRITE;

@Service
@Transactional
public class JobInspectionService {

    private final JdbcTemplate jdbc;
    private final ProductionSupport support;
    private final ProductionFeatureFlags flags;
    private final ProductionMetrics metrics;
    private final ProductionQueryService query;
    private final AuditService audit;

    public JobInspectionService(JdbcTemplate jdbc, ProductionSupport support, ProductionFeatureFlags flags,
                                ProductionMetrics metrics, ProductionQueryService query, AuditService audit) {
        this.jdbc = jdbc;
        this.support = support;
        this.flags = flags;
        this.metrics = metrics;
        this.query = query;
        this.audit = audit;
    }

    @PreAuthorize(QUALITY_WRITE)
    public ProductionJobResponse open(UUID jobId, UUID stageId) {
        BossContext context = support.requireContext();
        flags.require(context.tenantId(), ProductionFeatureFlags.INSPECTION_GATE);
        StageRef stage = requireStage(context.tenantId(), jobId, stageId);
        if (!stage.inspection()) {
            throw ProductionSupport.badRequest("This stage does not require inspection");
        }
        Integer pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM quality_inspections
                WHERE tenant_id = ? AND reference_type = 'job_stage' AND reference_id = ?
                  AND status IN ('pending', 'in_progress')
                """, Integer.class, context.tenantId(), stageId);
        if (pending != null && pending > 0) {
            return query.get(jobId);
        }
        UUID inspectionId = UUID.randomUUID();
        String number = "INS-" + inspectionId.toString().substring(0, 8).toUpperCase();
        String type = stage.last() ? "final" : "in_process";
        jdbc.update("""
                INSERT INTO quality_inspections
                  (id, tenant_id, inspection_number, inspection_type, reference_type, reference_id,
                   item_id, quantity_inspected, status)
                VALUES (?, ?, ?, ?, 'job_stage', ?, ?, ?, 'pending')
                """, inspectionId, context.tenantId(), number, type, stageId, stage.itemId(), stage.quantity());
        audit.record("quality_inspection", inspectionId, "CREATE", null, "{\"status\":\"pending\"}");
        return query.get(jobId);
    }

    @PreAuthorize(QUALITY_WRITE)
    public ProductionJobResponse record(UUID jobId, UUID inspectionId, InspectionResultRequest request) {
        BossContext context = support.requireContext();
        flags.require(context.tenantId(), ProductionFeatureFlags.INSPECTION_GATE);
        InspectionRef inspection = requireInspection(context.tenantId(), jobId, inspectionId);
        if (!"pending".equals(inspection.status()) && !"in_progress".equals(inspection.status())) {
            throw ProductionSupport.conflict("Inspection result is already recorded");
        }
        jdbc.update("""
                UPDATE quality_inspections
                SET status = 'completed', result = ?, quantity_accepted = ?, quantity_rejected = ?,
                    inspector_id = ?, inspection_date = ?, notes = ?, updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """, request.result(), quantity(request.quantityAccepted()), quantity(request.quantityRejected()),
                context.userId(), LocalDate.now(), note(request.note()), context.tenantId(), inspectionId);
        if ("fail".equals(request.result())) {
            insertNcr(context, inspection, request.note());
            metrics.afterCommit(() -> metrics.inspectionGate("failed"));
        }
        audit.record("quality_inspection", inspectionId, "RESULT", null,
                "{\"result\":\"" + request.result() + "\"}");
        return query.get(jobId);
    }

    @PreAuthorize(CONCESSION_APPROVE)
    public ProductionJobResponse concede(UUID jobId, UUID inspectionId, ConcessionRequest request) {
        BossContext context = support.requireContext();
        flags.require(context.tenantId(), ProductionFeatureFlags.INSPECTION_GATE);
        InspectionRef inspection = requireInspection(context.tenantId(), jobId, inspectionId);
        UUID ncrId = jdbc.query("""
                SELECT id FROM ncrs WHERE tenant_id = ? AND inspection_id = ? ORDER BY created_at DESC LIMIT 1
                """, rs -> rs.next() ? rs.getObject("id", UUID.class) : null, context.tenantId(), inspectionId);
        if (ncrId == null) {
            throw ProductionSupport.badRequest("A non-conformance record is required before a concession");
        }
        jdbc.update("""
                INSERT INTO concessions
                  (id, tenant_id, ncr_id, concession_type, approved_by, approval_notes, status)
                VALUES (?, ?, ?, ?, ?, ?, 'approved')
                """, UUID.randomUUID(), context.tenantId(), ncrId, request.concessionType(),
                context.userId(), request.note().trim());
        audit.record("concession", ncrId, "APPROVE", null, "{\"status\":\"approved\"}");
        return query.get(jobId);
    }

    private void insertNcr(BossContext context, InspectionRef inspection, String note) {
        UUID ncrId = UUID.randomUUID();
        String number = "NCR-" + ncrId.toString().substring(0, 8).toUpperCase();
        BigDecimal qty = inspection.quantity() == null ? BigDecimal.ONE : inspection.quantity();
        jdbc.update("""
                INSERT INTO ncrs
                  (id, tenant_id, ncr_number, inspection_id, item_id, quantity, defect_type,
                   defect_description, status, raised_by)
                VALUES (?, ?, ?, ?, ?, ?, 'in_process', ?, 'open', ?)
                """, ncrId, context.tenantId(), number, inspection.id(), inspection.itemId(), qty,
                note == null ? "In-process inspection failed" : note, context.userId());
    }

    private StageRef requireStage(UUID tenantId, UUID jobId, UUID stageId) {
        StageRef stage = jdbc.query("""
                SELECT s.id, s.requires_inspection, j.finished_item_id, j.quantity,
                       s.seq_no = (SELECT MAX(seq_no) FROM job_stages WHERE tenant_id = s.tenant_id AND job_id = s.job_id)
                         AS last_stage
                FROM job_stages s
                JOIN production_jobs j ON j.id = s.job_id AND j.tenant_id = s.tenant_id
                WHERE s.tenant_id = ? AND s.job_id = ? AND s.id = ?
                """, rs -> rs.next() ? new StageRef(rs.getBoolean("requires_inspection"),
                rs.getObject("finished_item_id", UUID.class), rs.getBigDecimal("quantity"),
                rs.getBoolean("last_stage")) : null, tenantId, jobId, stageId);
        if (stage == null) {
            throw ProductionSupport.notFound("Job stage not found");
        }
        return stage;
    }

    private InspectionRef requireInspection(UUID tenantId, UUID jobId, UUID inspectionId) {
        InspectionRef inspection = jdbc.query("""
                SELECT q.id, q.status, q.item_id, q.quantity_inspected
                FROM quality_inspections q
                JOIN job_stages s ON s.id = q.reference_id AND s.tenant_id = q.tenant_id
                WHERE q.tenant_id = ? AND q.id = ? AND s.job_id = ? AND q.reference_type = 'job_stage'
                """, rs -> rs.next() ? new InspectionRef(rs.getObject("id", UUID.class),
                rs.getString("status"), rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("quantity_inspected")) : null, tenantId, inspectionId, jobId);
        if (inspection == null) {
            throw ProductionSupport.notFound("Inspection was not found on this job");
        }
        return inspection;
    }

    private static BigDecimal quantity(BigDecimal value) {
        return value == null ? null : value.setScale(3, java.math.RoundingMode.HALF_UP);
    }

    private static String note(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record StageRef(boolean inspection, UUID itemId, BigDecimal quantity, boolean last) {}

    private record InspectionRef(UUID id, String status, UUID itemId, BigDecimal quantity) {}
}
