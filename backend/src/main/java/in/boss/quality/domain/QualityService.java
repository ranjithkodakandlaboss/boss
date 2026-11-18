package in.boss.quality.domain;

import in.boss.quality.api.QualityDtos.*;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import in.boss.shared.security.BossPermissions;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@Transactional
public class QualityService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public QualityService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ===== INSPECTION LIST AND DETAIL =====

    @Transactional(readOnly = true)
    @PreAuthorize(BossPermissions.QUALITY_ACCESS)
    public List<InspectionSummary> listInspections(String type, String status, String q) {
        BossContext ctx = requireContext();
        return jdbc.query("""
                SELECT qi.id, qi.inspection_number, qi.inspection_type, qi.reference_type, qi.reference_id,
                       qi.item_id, i.code AS item_code, i.name AS item_name, qi.lot_id,
                       qi.quantity_inspected, qi.quantity_accepted, qi.quantity_rejected,
                       qi.result, qi.status, qi.inspector_id,
                       au.display_name AS inspector_name,
                       qi.inspection_date, qi.notes,
                       qi.inspection_template_id AS template_id, ipt.name AS template_name,
                       qi.created_at,
                       COALESCE(g.grn_number, pj.job_number) AS reference_label
                FROM quality_inspections qi
                LEFT JOIN items i ON i.id = qi.item_id AND i.tenant_id = qi.tenant_id
                LEFT JOIN app_users au ON au.id = qi.inspector_id
                LEFT JOIN inspection_parameter_templates ipt ON ipt.id = qi.inspection_template_id
                LEFT JOIN grns g ON g.id = qi.reference_id AND g.tenant_id = qi.tenant_id
                     AND qi.reference_type = 'grn'
                LEFT JOIN production_jobs pj ON pj.id = qi.reference_id AND pj.tenant_id = qi.tenant_id
                     AND qi.reference_type IN ('production_job')
                WHERE qi.tenant_id = ?
                  AND (? IS NULL OR qi.inspection_type = ?)
                  AND (? IS NULL OR qi.status = ?)
                  AND (? IS NULL OR qi.inspection_number ILIKE '%' || ? || '%'
                       OR i.name ILIKE '%' || ? || '%'
                       OR i.code ILIKE '%' || ? || '%')
                ORDER BY qi.created_at DESC
                LIMIT 500
                """,
                (rs, row) -> mapSummary(rs),
                ctx.tenantId(),
                type, type,
                status, status,
                q, q, q, q);
    }

    @Transactional(readOnly = true)
    @PreAuthorize(BossPermissions.QUALITY_ACCESS)
    public InspectionDetail getInspection(UUID inspectionId) {
        BossContext ctx = requireContext();
        List<InspectionDetail> rows = jdbc.query("""
                SELECT qi.id, qi.inspection_number, qi.inspection_type, qi.reference_type, qi.reference_id,
                       qi.item_id, i.code AS item_code, i.name AS item_name, qi.lot_id,
                       qi.quantity_inspected, qi.quantity_accepted, qi.quantity_rejected,
                       qi.result, qi.status, qi.inspector_id,
                       au.display_name AS inspector_name,
                       qi.inspection_date, qi.notes,
                       qi.inspection_template_id AS template_id, ipt.name AS template_name,
                       qi.created_at, qi.updated_at,
                       COALESCE(g.grn_number, pj.job_number) AS reference_label
                FROM quality_inspections qi
                LEFT JOIN items i ON i.id = qi.item_id AND i.tenant_id = qi.tenant_id
                LEFT JOIN app_users au ON au.id = qi.inspector_id
                LEFT JOIN inspection_parameter_templates ipt ON ipt.id = qi.inspection_template_id
                LEFT JOIN grns g ON g.id = qi.reference_id AND g.tenant_id = qi.tenant_id
                     AND qi.reference_type = 'grn'
                LEFT JOIN production_jobs pj ON pj.id = qi.reference_id AND pj.tenant_id = qi.tenant_id
                     AND qi.reference_type IN ('production_job')
                WHERE qi.tenant_id = ? AND qi.id = ?
                """,
                (rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    List<ChecklistResponseDto> responses = listChecklistResponses(ctx.tenantId(), id);
                    List<NcrSummary> ncrs = listNcrsForInspection(ctx.tenantId(), id);
                    return new InspectionDetail(
                            id,
                            rs.getString("inspection_number"),
                            rs.getString("inspection_type"),
                            rs.getString("reference_type"),
                            rs.getObject("reference_id", UUID.class),
                            rs.getString("reference_label"),
                            rs.getObject("item_id", UUID.class),
                            rs.getString("item_code"),
                            rs.getString("item_name"),
                            rs.getObject("lot_id", UUID.class),
                            rs.getBigDecimal("quantity_inspected"),
                            rs.getBigDecimal("quantity_accepted"),
                            rs.getBigDecimal("quantity_rejected"),
                            rs.getString("result"),
                            rs.getString("status"),
                            rs.getObject("inspector_id", UUID.class),
                            rs.getString("inspector_name"),
                            rs.getObject("inspection_date", LocalDate.class),
                            rs.getString("notes"),
                            rs.getObject("template_id", UUID.class),
                            rs.getString("template_name"),
                            responses,
                            ncrs,
                            rs.getObject("created_at", java.time.Instant.class),
                            rs.getObject("updated_at", java.time.Instant.class));
                },
                ctx.tenantId(), inspectionId);
        if (rows.isEmpty()) {
            throw notFound("Inspection not found");
        }
        return rows.get(0);
    }

    @PreAuthorize(BossPermissions.QUALITY_WRITE)
    public InspectionDetail createInspection(CreateInspectionRequest request) {
        BossContext ctx = requireContext();
        validateInspectionReference(ctx.tenantId(), request.referenceType(), request.referenceId(),
                request.inspectionType());
        validateItem(ctx.tenantId(), request.itemId());

        UUID id = UUID.randomUUID();
        String number = "INSP-" + LocalDate.now().toString().replace("-", "")
                + "-" + id.toString().substring(0, 6).toUpperCase(Locale.ROOT);

        UUID templateId = request.templateId();
        if (templateId != null) {
            validateTemplate(ctx.tenantId(), templateId, request.inspectionType());
        }

        jdbc.update("""
                INSERT INTO quality_inspections
                  (id, tenant_id, inspection_number, inspection_type, reference_type, reference_id,
                   item_id, lot_id, quantity_inspected, status, inspector_id, inspection_date,
                   notes, inspection_template_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?)
                """,
                id, ctx.tenantId(), number,
                request.inspectionType(), request.referenceType(), request.referenceId(),
                request.itemId(), request.lotId(),
                request.quantityInspected(),
                request.inspectorId() != null ? request.inspectorId() : ctx.userId(),
                request.inspectionDate() != null ? request.inspectionDate() : LocalDate.now(),
                request.notes(), templateId);

        if (templateId != null) {
            snapshotChecklistItems(ctx, id, templateId);
        }

        audit.record("quality_inspections", id, "CREATE", null,
                "{\"type\":\"" + request.inspectionType() + "\"}");
        return getInspection(id);
    }

    @PreAuthorize(BossPermissions.QUALITY_WRITE)
    public InspectionDetail recordResult(UUID inspectionId, RecordResultRequest request) {
        BossContext ctx = requireContext();
        InspectionDetail current = getInspection(inspectionId);

        if ("completed".equals(current.status()) || "cancelled".equals(current.status())) {
            throw conflict("Inspection is already " + current.status());
        }

        // Validate result value
        String result = request.result();
        if (result != null && !List.of("pass", "fail", "conditional").contains(result)) {
            throw badRequest("Invalid result: must be pass, fail, or conditional");
        }

        // Validate quantities
        BigDecimal accepted = request.quantityAccepted();
        BigDecimal rejected = request.quantityRejected();
        if (accepted != null && rejected != null) {
            BigDecimal total = accepted.add(rejected);
            if (total.compareTo(current.quantityInspected()) > 0) {
                throw badRequest("Accepted + rejected quantities exceed quantity inspected");
            }
        }

        // Check mandatory checklist responses
        if (!current.checklistResponses().isEmpty()) {
            validateMandatoryResponses(ctx.tenantId(), inspectionId, request.checklistResponses());
        }

        // Save checklist responses
        if (request.checklistResponses() != null) {
            saveChecklistResponses(ctx, inspectionId, request.checklistResponses());
        }

        String newStatus = "in_progress";
        if (result != null) {
            newStatus = "completed";
        }

        jdbc.update("""
                UPDATE quality_inspections
                SET result = ?, status = ?, quantity_accepted = COALESCE(?, quantity_accepted),
                    quantity_rejected = COALESCE(?, quantity_rejected),
                    inspector_id = ?, inspection_date = COALESCE(?, inspection_date),
                    notes = COALESCE(?, notes), updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """,
                result, newStatus,
                accepted, rejected,
                ctx.userId(),
                LocalDate.now(),
                request.notes(),
                ctx.tenantId(), inspectionId);

        // Auto-create NCR for failed inspections on job stages
        if ("fail".equals(result) && "job_stage".equals(current.referenceType())) {
            if (listNcrsForInspection(ctx.tenantId(), inspectionId).isEmpty()) {
                createNcrForFailedInspection(ctx, current, rejected);
            }
        }

        audit.record("quality_inspections", inspectionId, "RESULT",
                "{\"status\":\"" + current.status() + "\"}",
                "{\"result\":\"" + result + "\",\"status\":\"" + newStatus + "\"}");
        return getInspection(inspectionId);
    }

    // ===== NCR =====

    @Transactional(readOnly = true)
    @PreAuthorize(BossPermissions.QUALITY_ACCESS)
    public List<NcrSummary> listNcrs(String status, String q) {
        BossContext ctx = requireContext();
        return jdbc.query("""
                SELECT n.id, n.ncr_number, n.inspection_id, n.item_id,
                       i.code AS item_code, i.name AS item_name,
                       n.lot_id, n.job_id, n.defect_category_id, co.label AS defect_category_label,
                       n.defect_description, n.severity, n.quantity, n.status, n.disposition,
                       n.raised_by, au.display_name AS raised_by_name,
                       n.created_at,
                       c.status AS concession_status
                FROM ncrs n
                LEFT JOIN items i ON i.id = n.item_id AND i.tenant_id = n.tenant_id
                LEFT JOIN app_users au ON au.id = n.raised_by
                LEFT JOIN config_options co ON co.id = n.defect_category_id
                LEFT JOIN concessions c ON c.ncr_id = n.id AND c.tenant_id = n.tenant_id
                WHERE n.tenant_id = ?
                  AND (? IS NULL OR n.status = ?)
                  AND (? IS NULL OR n.ncr_number ILIKE '%' || ? || '%'
                       OR i.name ILIKE '%' || ? || '%'
                       OR i.code ILIKE '%' || ? || '%'
                       OR n.defect_description ILIKE '%' || ? || '%')
                ORDER BY n.created_at DESC
                LIMIT 500
                """,
                (rs, row) -> mapNcrSummary(rs),
                ctx.tenantId(),
                status, status,
                q, q, q, q, q);
    }

    @Transactional(readOnly = true)
    @PreAuthorize(BossPermissions.QUALITY_ACCESS)
    public NcrDetail getNcr(UUID ncrId) {
        BossContext ctx = requireContext();
        List<NcrDetail> rows = jdbc.query("""
                SELECT n.id, n.ncr_number, n.inspection_id, qi.inspection_number,
                       n.item_id, i.code AS item_code, i.name AS item_name,
                       n.lot_id, n.job_id, pj.job_number,
                       n.defect_category_id, co.label AS defect_category_label,
                       n.defect_description, n.severity, n.quantity,
                       n.root_cause, n.status, n.disposition, n.resolution,
                       n.raised_by, ru.display_name AS raised_by_name,
                       n.resolved_by, rv.display_name AS resolved_by_name,
                       n.resolved_at, n.created_at, n.updated_at
                FROM ncrs n
                LEFT JOIN quality_inspections qi ON qi.id = n.inspection_id AND qi.tenant_id = n.tenant_id
                LEFT JOIN items i ON i.id = n.item_id AND i.tenant_id = n.tenant_id
                LEFT JOIN production_jobs pj ON pj.id = n.job_id AND pj.tenant_id = n.tenant_id
                LEFT JOIN config_options co ON co.id = n.defect_category_id
                LEFT JOIN app_users ru ON ru.id = n.raised_by
                LEFT JOIN app_users rv ON rv.id = n.resolved_by
                WHERE n.tenant_id = ? AND n.id = ?
                """,
                (rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    List<ConcessionDto> concessions = listConcessionsForNcr(ctx.tenantId(), id);
                    return new NcrDetail(
                            id, rs.getString("ncr_number"),
                            rs.getObject("inspection_id", UUID.class),
                            rs.getString("inspection_number"),
                            rs.getObject("item_id", UUID.class),
                            rs.getString("item_code"), rs.getString("item_name"),
                            rs.getObject("lot_id", UUID.class),
                            rs.getObject("job_id", UUID.class),
                            rs.getString("job_number"),
                            rs.getObject("defect_category_id", UUID.class),
                            rs.getString("defect_category_label"),
                            rs.getString("defect_description"),
                            rs.getString("severity"), rs.getBigDecimal("quantity"),
                            rs.getString("root_cause"), rs.getString("status"),
                            rs.getString("disposition"), rs.getString("resolution"),
                            rs.getObject("raised_by", UUID.class), rs.getString("raised_by_name"),
                            rs.getObject("resolved_by", UUID.class), rs.getString("resolved_by_name"),
                            rs.getObject("resolved_at", java.time.Instant.class),
                            concessions,
                            rs.getObject("created_at", java.time.Instant.class),
                            rs.getObject("updated_at", java.time.Instant.class));
                },
                ctx.tenantId(), ncrId);
        if (rows.isEmpty()) {
            throw notFound("NCR not found");
        }
        return rows.get(0);
    }

    @PreAuthorize(BossPermissions.QUALITY_WRITE)
    public NcrDetail createNcr(CreateNcrRequest request) {
        BossContext ctx = requireContext();

        if (request.inspectionId() == null) {
            throw badRequest("inspectionId is required");
        }
        if (request.itemId() == null) {
            throw badRequest("itemId is required");
        }
        // Verify inspection belongs to this tenant
        Integer inspFound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM quality_inspections WHERE tenant_id = ? AND id = ?",
                Integer.class, ctx.tenantId(), request.inspectionId());
        if (inspFound == null || inspFound == 0) {
            throw notFound("Inspection not found");
        }
        validateItem(ctx.tenantId(), request.itemId());

        UUID id = UUID.randomUUID();
        String number = "NCR-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);

        jdbc.update("""
                INSERT INTO ncrs
                  (id, tenant_id, ncr_number, inspection_id, item_id, lot_id, job_id,
                   quantity, defect_category_id, defect_description, severity, root_cause,
                   status, raised_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', ?)
                """,
                id, ctx.tenantId(), number,
                request.inspectionId(), request.itemId(), request.lotId(), request.jobId(),
                request.quantity() != null ? request.quantity() : BigDecimal.ONE,
                request.defectCategoryId(),
                request.defectDescription(),
                request.severity(),
                request.rootCause(),
                ctx.userId());

        audit.record("ncrs", id, "CREATE", null, null);
        return getNcr(id);
    }

    @PreAuthorize(BossPermissions.QUALITY_WRITE)
    public NcrDetail updateNcr(UUID ncrId, UpdateNcrRequest request) {
        BossContext ctx = requireContext();
        NcrDetail current = getNcr(ncrId);

        if ("closed".equals(current.status()) || "scrapped".equals(current.status())) {
            throw conflict("NCR is already " + current.status());
        }

        jdbc.update("""
                UPDATE ncrs
                SET status = COALESCE(?, status),
                    disposition = COALESCE(?, disposition),
                    root_cause = COALESCE(?, root_cause),
                    resolution = COALESCE(?, resolution),
                    resolved_by = CASE WHEN ? IN ('closed','scrapped') THEN ? ELSE resolved_by END,
                    resolved_at = CASE WHEN ? IN ('closed','scrapped') THEN NOW() ELSE resolved_at END,
                    updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """,
                request.status(), request.disposition(), request.rootCause(), request.resolution(),
                request.status(), ctx.userId(),
                request.status(),
                ctx.tenantId(), ncrId);

        audit.record("ncrs", ncrId, "UPDATE",
                "{\"status\":\"" + current.status() + "\"}",
                "{\"status\":\"" + request.status() + "\"}");
        return getNcr(ncrId);
    }

    // ===== CONCESSION =====

    @PreAuthorize(BossPermissions.CONCESSION_APPROVE)
    public NcrDetail createConcession(UUID ncrId, CreateConcessionRequest request) {
        BossContext ctx = requireContext();
        NcrDetail ncr = getNcr(ncrId);

        if ("closed".equals(ncr.status()) || "scrapped".equals(ncr.status())) {
            throw conflict("Cannot grant concession on a closed or scrapped NCR");
        }
        if (request.justification() == null || request.justification().isBlank()) {
            throw badRequest("Justification is required for concession approval");
        }

        UUID concessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO concessions
                  (id, tenant_id, ncr_id, approval_notes, approved_by, status)
                VALUES (?, ?, ?, ?, ?, 'approved')
                """,
                concessionId, ctx.tenantId(), ncrId,
                request.justification().trim(), ctx.userId());

        jdbc.update("""
                UPDATE ncrs SET status = 'under_review', updated_at = NOW()
                WHERE tenant_id = ? AND id = ? AND status = 'open'
                """, ctx.tenantId(), ncrId);

        audit.record("concessions", concessionId, "APPROVED", null,
                "{\"ncrId\":\"" + ncrId + "\"}");
        return getNcr(ncrId);
    }

    // ===== CLEARANCE CERTIFICATES =====

    @Transactional(readOnly = true)
    @PreAuthorize(BossPermissions.QUALITY_ACCESS)
    public List<ClearanceCertificateDto> listClearanceCertificates(UUID jobId) {
        BossContext ctx = requireContext();
        return jdbc.query("""
                SELECT cc.id, cc.cert_number, cc.job_id, pj.job_number, cc.customer_id,
                       c.name AS customer_name, cc.status, cc.issued_at, cc.issued_by,
                       au.display_name AS issued_by_name,
                       cc.issued_by_customer_contact, cc.customer_ref, cc.notes, cc.created_at
                FROM clearance_certificates cc
                LEFT JOIN production_jobs pj ON pj.id = cc.job_id AND pj.tenant_id = cc.tenant_id
                LEFT JOIN customers c ON c.id = cc.customer_id AND c.tenant_id = cc.tenant_id
                LEFT JOIN app_users au ON au.id = cc.issued_by
                WHERE cc.tenant_id = ?
                  AND (? IS NULL OR cc.job_id = ?)
                ORDER BY cc.created_at DESC
                """,
                (rs, row) -> mapClearanceCert(rs),
                ctx.tenantId(), jobId, jobId);
    }

    @PreAuthorize(BossPermissions.QUALITY_WRITE)
    public ClearanceCertificateDto createClearanceCertificate(CreateClearanceCertificateRequest request) {
        BossContext ctx = requireContext();

        UUID jobId = request.jobId();
        UUID customerId = jdbc.query("""
                SELECT customer_id FROM production_jobs WHERE tenant_id = ? AND id = ?
                """, rs -> rs.next() ? rs.getObject("customer_id", UUID.class) : null,
                ctx.tenantId(), jobId);
        if (customerId == null) {
            throw notFound("Production job not found");
        }

        UUID id = UUID.randomUUID();
        String number = "CERT-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);

        jdbc.update("""
                INSERT INTO clearance_certificates
                  (id, tenant_id, cert_number, job_id, customer_id, status, issued_at, issued_by,
                   issued_by_customer_contact, customer_ref, notes)
                VALUES (?, ?, ?, ?, ?, 'issued', NOW(), ?, ?, ?, ?)
                """,
                id, ctx.tenantId(), number, jobId, customerId,
                ctx.userId(),
                request.issuedByCustomerContact(), request.customerRef(), request.notes());

        audit.record("clearance_certificates", id, "ISSUED", null,
                "{\"jobId\":\"" + jobId + "\"}");
        return getClearanceCertificate(ctx.tenantId(), id);
    }

    // ===== DISPATCH ELIGIBILITY =====

    @Transactional(readOnly = true)
    @PreAuthorize(BossPermissions.QUALITY_ACCESS)
    public DispatchEligibilityResponse checkDispatchEligibility(UUID jobId) {
        BossContext ctx = requireContext();
        return computeEligibility(ctx.tenantId(), jobId);
    }

    public DispatchEligibilityResponse computeEligibility(UUID tenantId, UUID jobId) {
        List<String> blockers = new ArrayList<>();

        // 1. Check if job exists
        Boolean jobExists = jdbc.queryForObject(
                "SELECT COUNT(*) > 0 FROM production_jobs WHERE tenant_id = ? AND id = ?",
                Boolean.class, tenantId, jobId);
        if (!Boolean.TRUE.equals(jobExists)) {
            return new DispatchEligibilityResponse(jobId, false, "Job not found",
                    List.of("Job not found"), null, null, null);
        }

        // 2. Check final / dispatch_readiness inspection
        String inspStatus = determineFinalInspectionStatus(tenantId, jobId);
        if (!"passed".equals(inspStatus) && !"concession_granted".equals(inspStatus)) {
            blockers.add("Final or dispatch-readiness inspection has not passed. Status: " + inspStatus);
        }

        // 3. Check unresolved NCRs that block dispatch
        Integer unresolvedNcrs = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ncrs n
                JOIN quality_inspections qi ON qi.id = n.inspection_id AND qi.tenant_id = n.tenant_id
                WHERE n.tenant_id = ? AND qi.reference_id = ?
                  AND n.status NOT IN ('resolved','closed','scrapped')
                  AND NOT EXISTS (
                    SELECT 1 FROM concessions c WHERE c.ncr_id = n.id
                      AND c.tenant_id = n.tenant_id AND c.status = 'approved')
                """, Integer.class, tenantId, jobId);
        if (unresolvedNcrs != null && unresolvedNcrs > 0) {
            blockers.add(unresolvedNcrs + " unresolved NCR(s) without an approved concession");
        }

        // 4. Check clearance certificate (optional — only blocks if one exists and is not issued)
        ClearanceCertStatus certStatus = getClearanceCertStatus(tenantId, jobId);
        String certStatusStr = certStatus.status();
        UUID certId = certStatus.id();
        if ("pending".equals(certStatusStr) || "expired".equals(certStatusStr) || "revoked".equals(certStatusStr)) {
            blockers.add("Customer clearance certificate is required but status is: " + certStatusStr);
        }

        boolean eligible = blockers.isEmpty();
        String reason = eligible ? "All quality conditions satisfied"
                : "Blocked: " + String.join("; ", blockers);

        return new DispatchEligibilityResponse(jobId, eligible, reason, blockers,
                inspStatus, certStatusStr, certId);
    }

    // ===== CHECKLIST TEMPLATE ADMIN =====

    @Transactional(readOnly = true)
    @PreAuthorize(BossPermissions.OWNER_OR_OPS)
    public List<ChecklistTemplateDto> listTemplates(String scope, String inspectionType, Boolean activeOnly) {
        BossContext ctx = requireContext();
        return jdbc.query("""
                SELECT ipt.id, ipt.name, ipt.description, ipt.scope, ipt.inspection_type,
                       ipt.is_active, ipt.created_at
                FROM inspection_parameter_templates ipt
                WHERE ipt.tenant_id = ?
                  AND (? IS NULL OR ipt.scope = ?)
                  AND (? IS NULL OR ipt.inspection_type = ? OR ipt.inspection_type = 'any')
                  AND (? IS FALSE OR ipt.is_active = TRUE)
                ORDER BY ipt.name
                """,
                (rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    List<ChecklistItemDto> items = listChecklistItems(ctx.tenantId(), id);
                    return new ChecklistTemplateDto(id,
                            rs.getString("name"), rs.getString("description"),
                            rs.getString("scope"), rs.getString("inspection_type"),
                            rs.getBoolean("is_active"), items,
                            rs.getObject("created_at", java.time.Instant.class));
                },
                ctx.tenantId(), scope, scope, inspectionType, inspectionType,
                Boolean.TRUE.equals(activeOnly));
    }

    @Transactional(readOnly = true)
    @PreAuthorize(BossPermissions.QUALITY_ACCESS)
    public ChecklistTemplateDto getTemplate(UUID templateId) {
        BossContext ctx = requireContext();
        return fetchTemplate(ctx.tenantId(), templateId);
    }

    @PreAuthorize(BossPermissions.OWNER_OR_OPS)
    public ChecklistTemplateDto createTemplate(CreateChecklistTemplateRequest request) {
        BossContext ctx = requireContext();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inspection_parameter_templates
                  (id, tenant_id, name, description, scope, inspection_type, is_active,
                   parameter_name, spec_type)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, 'text')
                """,
                id, ctx.tenantId(), request.name(), request.description(),
                request.scope(), request.inspectionType(), request.name());

        if (request.items() != null) {
            saveChecklistItemsForTemplate(ctx.tenantId(), id, request.items());
        }

        audit.record("inspection_parameter_templates", id, "CREATE", null, null);
        return fetchTemplate(ctx.tenantId(), id);
    }

    @PreAuthorize(BossPermissions.OWNER_OR_OPS)
    public ChecklistTemplateDto updateTemplate(UUID templateId, UpdateChecklistTemplateRequest request) {
        BossContext ctx = requireContext();
        fetchTemplate(ctx.tenantId(), templateId); // validates exists

        jdbc.update("""
                UPDATE inspection_parameter_templates
                SET name = COALESCE(?, name),
                    description = COALESCE(?, description),
                    scope = COALESCE(?, scope),
                    inspection_type = COALESCE(?, inspection_type),
                    is_active = COALESCE(?, is_active)
                WHERE tenant_id = ? AND id = ?
                """,
                request.name(), request.description(), request.scope(),
                request.inspectionType(), request.isActive(),
                ctx.tenantId(), templateId);

        if (request.items() != null) {
            // Replace items (delete and re-insert) — existing inspection responses are preserved via snapshot
            jdbc.update("DELETE FROM checklist_items WHERE tenant_id = ? AND template_id = ?",
                    ctx.tenantId(), templateId);
            saveChecklistItemsForTemplate(ctx.tenantId(), templateId, request.items());
        }

        audit.record("inspection_parameter_templates", templateId, "UPDATE", null, null);
        return fetchTemplate(ctx.tenantId(), templateId);
    }

    // ===== PRIVATE HELPERS =====

    private List<ChecklistItemDto> listChecklistItems(UUID tenantId, UUID templateId) {
        return jdbc.query("""
                SELECT id, sort_order, description, response_type, is_mandatory,
                       unit, spec_min, spec_max
                FROM checklist_items
                WHERE tenant_id = ? AND template_id = ?
                ORDER BY sort_order
                """,
                (rs, row) -> new ChecklistItemDto(
                        rs.getObject("id", UUID.class),
                        rs.getInt("sort_order"),
                        rs.getString("description"),
                        rs.getString("response_type"),
                        rs.getBoolean("is_mandatory"),
                        rs.getString("unit"),
                        rs.getBigDecimal("spec_min"),
                        rs.getBigDecimal("spec_max")),
                tenantId, templateId);
    }

    private List<ChecklistResponseDto> listChecklistResponses(UUID tenantId, UUID inspectionId) {
        return jdbc.query("""
                SELECT id, checklist_item_id, item_description_snapshot AS item_description,
                       response_type, is_mandatory, response_value, response_pass, observation,
                       responded_at IS NOT NULL AS answered
                FROM inspection_checklist_responses
                WHERE tenant_id = ? AND inspection_id = ?
                ORDER BY (SELECT sort_order FROM checklist_items ci WHERE ci.id = checklist_item_id LIMIT 1)
                """,
                (rs, row) -> new ChecklistResponseDto(
                        rs.getObject("id", UUID.class),
                        rs.getObject("checklist_item_id", UUID.class),
                        rs.getString("item_description"),
                        rs.getString("response_type"),
                        rs.getBoolean("is_mandatory"),
                        rs.getString("response_value"),
                        (Boolean) rs.getObject("response_pass"),
                        rs.getString("observation"),
                        rs.getBoolean("answered")),
                tenantId, inspectionId);
    }

    private List<NcrSummary> listNcrsForInspection(UUID tenantId, UUID inspectionId) {
        return jdbc.query("""
                SELECT n.id, n.ncr_number, n.inspection_id, n.item_id,
                       i.code AS item_code, i.name AS item_name,
                       n.lot_id, n.job_id, n.defect_category_id, co.label AS defect_category_label,
                       n.defect_description, n.severity, n.quantity, n.status, n.disposition,
                       n.raised_by, au.display_name AS raised_by_name,
                       n.created_at,
                       c.status AS concession_status
                FROM ncrs n
                LEFT JOIN items i ON i.id = n.item_id AND i.tenant_id = n.tenant_id
                LEFT JOIN app_users au ON au.id = n.raised_by
                LEFT JOIN config_options co ON co.id = n.defect_category_id
                LEFT JOIN concessions c ON c.ncr_id = n.id AND c.tenant_id = n.tenant_id
                WHERE n.tenant_id = ? AND n.inspection_id = ?
                ORDER BY n.created_at
                """,
                (rs, row) -> mapNcrSummary(rs),
                tenantId, inspectionId);
    }

    private List<ConcessionDto> listConcessionsForNcr(UUID tenantId, UUID ncrId) {
        return jdbc.query("""
                SELECT c.id, c.ncr_id, c.approval_notes, c.approved_by,
                       au.display_name AS approved_by_name, c.status, c.created_at
                FROM concessions c
                LEFT JOIN app_users au ON au.id = c.approved_by
                WHERE c.tenant_id = ? AND c.ncr_id = ?
                ORDER BY c.created_at DESC
                """,
                (rs, row) -> new ConcessionDto(
                        rs.getObject("id", UUID.class),
                        rs.getObject("ncr_id", UUID.class),
                        rs.getString("approval_notes"),
                        rs.getObject("approved_by", UUID.class),
                        rs.getString("approved_by_name"),
                        rs.getString("approval_notes"),
                        rs.getString("status"),
                        rs.getObject("created_at", java.time.Instant.class)),
                tenantId, ncrId);
    }

    private ClearanceCertificateDto getClearanceCertificate(UUID tenantId, UUID certId) {
        return jdbc.query("""
                SELECT cc.id, cc.cert_number, cc.job_id, pj.job_number, cc.customer_id,
                       c.name AS customer_name, cc.status, cc.issued_at, cc.issued_by,
                       au.display_name AS issued_by_name,
                       cc.issued_by_customer_contact, cc.customer_ref, cc.notes, cc.created_at
                FROM clearance_certificates cc
                LEFT JOIN production_jobs pj ON pj.id = cc.job_id AND pj.tenant_id = cc.tenant_id
                LEFT JOIN customers c ON c.id = cc.customer_id AND c.tenant_id = cc.tenant_id
                LEFT JOIN app_users au ON au.id = cc.issued_by
                WHERE cc.tenant_id = ? AND cc.id = ?
                """,
                rs -> {
                    if (!rs.next()) throw notFound("Clearance certificate not found");
                    return mapClearanceCert(rs);
                },
                tenantId, certId);
    }

    private ClearanceCertificateDto mapClearanceCert(ResultSet rs) throws SQLException {
        return new ClearanceCertificateDto(
                rs.getObject("id", UUID.class),
                rs.getString("cert_number"),
                rs.getObject("job_id", UUID.class),
                rs.getString("job_number"),
                rs.getObject("customer_id", UUID.class),
                rs.getString("customer_name"),
                rs.getString("status"),
                rs.getObject("issued_at", java.time.Instant.class),
                rs.getObject("issued_by", UUID.class),
                rs.getString("issued_by_name"),
                rs.getString("issued_by_customer_contact"),
                rs.getString("customer_ref"),
                rs.getString("notes"),
                rs.getObject("created_at", java.time.Instant.class));
    }

    private String determineFinalInspectionStatus(UUID tenantId, UUID jobId) {
        // Look for completed final or dispatch_readiness inspection with pass result
        Integer passed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM quality_inspections
                WHERE tenant_id = ? AND reference_type = 'production_job' AND reference_id = ?
                  AND inspection_type IN ('final','dispatch_readiness')
                  AND status = 'completed' AND result = 'pass'
                """, Integer.class, tenantId, jobId);
        if (passed != null && passed > 0) return "passed";

        // Check for concession on a failed final inspection
        Integer conceded = jdbc.queryForObject("""
                SELECT COUNT(*) FROM concessions c
                JOIN ncrs n ON n.id = c.ncr_id AND n.tenant_id = c.tenant_id
                JOIN quality_inspections q ON q.id = n.inspection_id AND q.tenant_id = n.tenant_id
                WHERE c.tenant_id = ? AND c.status = 'approved'
                  AND q.reference_type = 'production_job' AND q.reference_id = ?
                  AND q.inspection_type IN ('final','dispatch_readiness')
                """, Integer.class, tenantId, jobId);
        if (conceded != null && conceded > 0) return "concession_granted";

        // Check if there's a pending/in_progress inspection
        Integer pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM quality_inspections
                WHERE tenant_id = ? AND reference_type = 'production_job' AND reference_id = ?
                  AND inspection_type IN ('final','dispatch_readiness')
                  AND status IN ('pending','in_progress')
                """, Integer.class, tenantId, jobId);
        if (pending != null && pending > 0) return "in_progress";

        // Check for failed
        Integer failed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM quality_inspections
                WHERE tenant_id = ? AND reference_type = 'production_job' AND reference_id = ?
                  AND inspection_type IN ('final','dispatch_readiness')
                  AND status = 'completed' AND result = 'fail'
                """, Integer.class, tenantId, jobId);
        if (failed != null && failed > 0) return "failed";

        return "not_started";
    }

    private record ClearanceCertStatus(UUID id, String status) {}

    private ClearanceCertStatus getClearanceCertStatus(UUID tenantId, UUID jobId) {
        return jdbc.query("""
                SELECT id, status FROM clearance_certificates
                WHERE tenant_id = ? AND job_id = ?
                ORDER BY created_at DESC LIMIT 1
                """,
                rs -> rs.next()
                        ? new ClearanceCertStatus(rs.getObject("id", UUID.class), rs.getString("status"))
                        : new ClearanceCertStatus(null, "not_required"),
                tenantId, jobId);
    }

    private void validateMandatoryResponses(UUID tenantId, UUID inspectionId,
                                             List<ChecklistResponseInput> incoming) {
        List<ChecklistResponseDto> existing = listChecklistResponses(tenantId, inspectionId);
        for (ChecklistResponseDto item : existing) {
            if (!item.isMandatory()) continue;
            boolean answered = incoming != null && incoming.stream()
                    .anyMatch(r -> item.checklistItemId().equals(r.checklistItemId())
                            && (r.responseValue() != null || r.responsePass() != null));
            if (!answered && !item.answered()) {
                throw badRequest("Mandatory checklist item not answered: " + item.itemDescription());
            }
        }
    }

    private void snapshotChecklistItems(BossContext ctx, UUID inspectionId, UUID templateId) {
        List<ChecklistItemDto> items = listChecklistItems(ctx.tenantId(), templateId);
        for (ChecklistItemDto item : items) {
            jdbc.update("""
                    INSERT INTO inspection_checklist_responses
                      (id, tenant_id, inspection_id, checklist_item_id,
                       item_description_snapshot, response_type, is_mandatory)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), ctx.tenantId(), inspectionId, item.id(),
                    item.description(), item.responseType(), item.isMandatory());
        }
    }

    private void saveChecklistResponses(BossContext ctx, UUID inspectionId,
                                         List<ChecklistResponseInput> responses) {
        if (responses == null) return;
        for (ChecklistResponseInput r : responses) {
            jdbc.update("""
                    UPDATE inspection_checklist_responses
                    SET response_value = ?, response_pass = ?, observation = ?,
                        responded_at = NOW(), updated_at = NOW()
                    WHERE tenant_id = ? AND inspection_id = ? AND checklist_item_id = ?
                    """,
                    r.responseValue(), r.responsePass(), r.observation(),
                    ctx.tenantId(), inspectionId, r.checklistItemId());
        }
    }

    private void saveChecklistItemsForTemplate(UUID tenantId, UUID templateId,
                                                List<ChecklistItemInput> items) {
        for (ChecklistItemInput item : items) {
            UUID itemId = item.id() != null ? item.id() : UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO checklist_items
                      (id, tenant_id, template_id, sort_order, description, response_type,
                       is_mandatory, unit, spec_min, spec_max)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    itemId, tenantId, templateId, item.sortOrder(), item.description(),
                    item.responseType(), item.isMandatory(), item.unit(),
                    item.specMin(), item.specMax());
        }
    }

    private ChecklistTemplateDto fetchTemplate(UUID tenantId, UUID templateId) {
        List<ChecklistTemplateDto> rows = jdbc.query("""
                SELECT ipt.id, ipt.name, ipt.description, ipt.scope, ipt.inspection_type,
                       ipt.is_active, ipt.created_at
                FROM inspection_parameter_templates ipt
                WHERE ipt.tenant_id = ? AND ipt.id = ?
                """,
                (rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    List<ChecklistItemDto> items = listChecklistItems(tenantId, id);
                    return new ChecklistTemplateDto(id,
                            rs.getString("name"), rs.getString("description"),
                            rs.getString("scope"), rs.getString("inspection_type"),
                            rs.getBoolean("is_active"), items,
                            rs.getObject("created_at", java.time.Instant.class));
                },
                tenantId, templateId);
        if (rows.isEmpty()) throw notFound("Checklist template not found");
        return rows.get(0);
    }

    private void validateInspectionReference(UUID tenantId, String refType, UUID refId, String inspType) {
        switch (refType) {
            case "grn" -> {
                Integer found = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM grns WHERE tenant_id = ? AND id = ?",
                        Integer.class, tenantId, refId);
                if (found == null || found == 0) throw notFound("GRN not found");
                if (!"incoming".equals(inspType)) throw badRequest("GRN references must use incoming inspection type");
            }
            case "job_stage" -> {
                Integer found = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM job_stages WHERE tenant_id = ? AND id = ?",
                        Integer.class, tenantId, refId);
                if (found == null || found == 0) throw notFound("Job stage not found");
            }
            case "production_job" -> {
                Integer found = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM production_jobs WHERE tenant_id = ? AND id = ?",
                        Integer.class, tenantId, refId);
                if (found == null || found == 0) throw notFound("Production job not found");
            }
            case "dispatch" -> {
                // dispatch reference is valid for dispatch_readiness inspections
            }
            default -> throw badRequest("Unknown reference type: " + refType);
        }
    }

    private void validateItem(UUID tenantId, UUID itemId) {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM items WHERE tenant_id = ? AND id = ?",
                Integer.class, tenantId, itemId);
        if (found == null || found == 0) throw notFound("Item not found");
    }

    private void validateTemplate(UUID tenantId, UUID templateId, String inspectionType) {
        Boolean active = jdbc.queryForObject("""
                SELECT is_active FROM inspection_parameter_templates
                WHERE tenant_id = ? AND id = ?
                """, Boolean.class, tenantId, templateId);
        if (active == null) throw notFound("Checklist template not found");
        if (!active) throw badRequest("Checklist template is not active");
        // Scope/type compatibility checked by the caller via listTemplates filter
    }

    private void createNcrForFailedInspection(BossContext ctx, InspectionDetail inspection,
                                               BigDecimal rejected) {
        UUID ncrId = UUID.randomUUID();
        String ncrNumber = "NCR-" + ncrId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        BigDecimal qty = rejected != null && rejected.signum() > 0 ? rejected
                : (inspection.quantityInspected() != null ? inspection.quantityInspected() : BigDecimal.ONE);
        jdbc.update("""
                INSERT INTO ncrs
                  (id, tenant_id, ncr_number, inspection_id, item_id, lot_id,
                   quantity, defect_description, severity, status, raised_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'major', 'open', ?)
                """,
                ncrId, ctx.tenantId(), ncrNumber,
                inspection.id(), inspection.itemId(), inspection.lotId(),
                qty, "In-process inspection failed", ctx.userId());
        audit.record("ncrs", ncrId, "CREATE", null, "{\"auto\":true}");
    }

    private InspectionSummary mapSummary(ResultSet rs) throws SQLException {
        return new InspectionSummary(
                rs.getObject("id", UUID.class),
                rs.getString("inspection_number"),
                rs.getString("inspection_type"),
                rs.getString("reference_type"),
                rs.getObject("reference_id", UUID.class),
                rs.getString("reference_label"),
                rs.getObject("item_id", UUID.class),
                rs.getString("item_code"),
                rs.getString("item_name"),
                rs.getObject("lot_id", UUID.class),
                rs.getBigDecimal("quantity_inspected"),
                rs.getBigDecimal("quantity_accepted"),
                rs.getBigDecimal("quantity_rejected"),
                rs.getString("result"),
                rs.getString("status"),
                rs.getObject("inspector_id", UUID.class),
                rs.getString("inspector_name"),
                rs.getObject("inspection_date", LocalDate.class),
                rs.getString("notes"),
                rs.getObject("template_id", UUID.class),
                rs.getString("template_name"),
                rs.getObject("created_at", java.time.Instant.class));
    }

    private NcrSummary mapNcrSummary(ResultSet rs) throws SQLException {
        return new NcrSummary(
                rs.getObject("id", UUID.class),
                rs.getString("ncr_number"),
                rs.getObject("inspection_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getString("item_code"),
                rs.getString("item_name"),
                rs.getObject("lot_id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getObject("defect_category_id", UUID.class),
                rs.getString("defect_category_label"),
                rs.getString("defect_description"),
                rs.getString("severity"),
                rs.getBigDecimal("quantity"),
                rs.getString("status"),
                rs.getString("disposition"),
                rs.getObject("raised_by", UUID.class),
                rs.getString("raised_by_name"),
                rs.getString("concession_status"),
                rs.getObject("created_at", java.time.Instant.class));
    }

    private BossContext requireContext() {
        BossContext ctx = BossContextHolder.get();
        if (ctx == null || ctx.tenantId() == null || ctx.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant user context required");
        }
        return ctx;
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }
}
