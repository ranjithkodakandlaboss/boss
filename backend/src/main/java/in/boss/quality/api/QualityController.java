package in.boss.quality.api;

import in.boss.quality.api.QualityDtos.*;
import in.boss.quality.domain.QualityService;
import in.boss.shared.security.BossPermissions;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/quality")
public class QualityController {

    private final QualityService service;

    public QualityController(QualityService service) {
        this.service = service;
    }

    // ===== INSPECTIONS =====

    @GetMapping("/inspections")
    @PreAuthorize(BossPermissions.QUALITY_ACCESS)
    public List<InspectionSummary> listInspections(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q) {
        return service.listInspections(type, status, q);
    }

    @GetMapping("/inspections/{inspectionId}")
    @PreAuthorize(BossPermissions.QUALITY_ACCESS)
    public InspectionDetail getInspection(@PathVariable UUID inspectionId) {
        return service.getInspection(inspectionId);
    }

    @PostMapping("/inspections")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.QUALITY_WRITE)
    public InspectionDetail createInspection(@RequestBody CreateInspectionRequest request) {
        return service.createInspection(request);
    }

    @PostMapping("/inspections/{inspectionId}/result")
    @PreAuthorize(BossPermissions.QUALITY_WRITE)
    public InspectionDetail recordResult(
            @PathVariable UUID inspectionId,
            @RequestBody RecordResultRequest request) {
        return service.recordResult(inspectionId, request);
    }

    // ===== NCRs =====

    @GetMapping("/ncrs")
    @PreAuthorize(BossPermissions.QUALITY_ACCESS)
    public List<NcrSummary> listNcrs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q) {
        return service.listNcrs(status, q);
    }

    @GetMapping("/ncrs/{ncrId}")
    @PreAuthorize(BossPermissions.QUALITY_ACCESS)
    public NcrDetail getNcr(@PathVariable UUID ncrId) {
        return service.getNcr(ncrId);
    }

    @PostMapping("/ncrs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.QUALITY_WRITE)
    public NcrDetail createNcr(@RequestBody CreateNcrRequest request) {
        return service.createNcr(request);
    }

    @PatchMapping("/ncrs/{ncrId}")
    @PreAuthorize(BossPermissions.QUALITY_WRITE)
    public NcrDetail updateNcr(@PathVariable UUID ncrId, @RequestBody UpdateNcrRequest request) {
        return service.updateNcr(ncrId, request);
    }

    @PostMapping("/ncrs/{ncrId}/concession")
    @PreAuthorize(BossPermissions.CONCESSION_APPROVE)
    public NcrDetail grantConcession(
            @PathVariable UUID ncrId,
            @RequestBody CreateConcessionRequest request) {
        return service.createConcession(ncrId, request);
    }

    // ===== CLEARANCE CERTIFICATES =====

    @GetMapping("/clearance-certificates")
    @PreAuthorize(BossPermissions.QUALITY_ACCESS)
    public List<ClearanceCertificateDto> listClearanceCertificates(
            @RequestParam(required = false) UUID jobId) {
        return service.listClearanceCertificates(jobId);
    }

    @PostMapping("/clearance-certificates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.QUALITY_WRITE)
    public ClearanceCertificateDto createClearanceCertificate(
            @RequestBody CreateClearanceCertificateRequest request) {
        return service.createClearanceCertificate(request);
    }

    // ===== DISPATCH ELIGIBILITY =====

    @GetMapping("/jobs/{jobId}/dispatch-eligibility")
    @PreAuthorize(BossPermissions.QUALITY_ACCESS)
    public DispatchEligibilityResponse checkDispatchEligibility(@PathVariable UUID jobId) {
        return service.checkDispatchEligibility(jobId);
    }

    // ===== CHECKLIST TEMPLATES (Admin) =====

    @GetMapping("/checklist-templates")
    @PreAuthorize(BossPermissions.QUALITY_ACCESS)
    public List<ChecklistTemplateDto> listTemplates(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String inspectionType,
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        return service.listTemplates(scope, inspectionType, activeOnly);
    }

    @GetMapping("/checklist-templates/{templateId}")
    @PreAuthorize(BossPermissions.QUALITY_ACCESS)
    public ChecklistTemplateDto getTemplate(@PathVariable UUID templateId) {
        return service.getTemplate(templateId);
    }

    @PostMapping("/checklist-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.OWNER_OR_OPS)
    public ChecklistTemplateDto createTemplate(@RequestBody CreateChecklistTemplateRequest request) {
        return service.createTemplate(request);
    }

    @PutMapping("/checklist-templates/{templateId}")
    @PreAuthorize(BossPermissions.OWNER_OR_OPS)
    public ChecklistTemplateDto updateTemplate(
            @PathVariable UUID templateId,
            @RequestBody UpdateChecklistTemplateRequest request) {
        return service.updateTemplate(templateId, request);
    }
}
