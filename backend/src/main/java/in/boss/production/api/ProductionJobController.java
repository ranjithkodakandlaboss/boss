package in.boss.production.api;

import in.boss.production.api.ProductionApiDtos.AddCostRequest;
import in.boss.production.api.ProductionApiDtos.BusinessMetrics;
import in.boss.production.api.ProductionApiDtos.ConcessionRequest;
import in.boss.production.api.ProductionApiDtos.CostEntryResponse;
import in.boss.production.api.ProductionApiDtos.CostingResponse;
import in.boss.production.api.ProductionApiDtos.CreateJobRequest;
import in.boss.production.api.ProductionApiDtos.CreateRoutingTemplateRequest;
import in.boss.production.api.ProductionApiDtos.EligibleCpoLine;
import in.boss.production.api.ProductionApiDtos.InspectionResultRequest;
import in.boss.production.api.ProductionApiDtos.IssueMaterialRequest;
import in.boss.production.api.ProductionApiDtos.MaterialMovementResponse;
import in.boss.production.api.ProductionApiDtos.PageResponse;
import in.boss.production.api.ProductionApiDtos.PlanPreviewResponse;
import in.boss.production.api.ProductionApiDtos.ProductionJobResponse;
import in.boss.production.api.ProductionApiDtos.ReasonRequest;
import in.boss.production.api.ProductionApiDtos.ReturnMaterialRequest;
import in.boss.production.api.ProductionApiDtos.ReviseRoutingTemplateRequest;
import in.boss.production.api.ProductionApiDtos.RoutingTemplateOption;
import in.boss.production.api.ProductionApiDtos.RoutingTemplateDetail;
import in.boss.production.api.ProductionApiDtos.SavePlanRequest;
import in.boss.production.api.ProductionApiDtos.StageActionRequest;
import in.boss.production.domain.JobCostingService;
import in.boss.production.domain.JobInspectionService;
import in.boss.production.domain.JobMaterialService;
import in.boss.production.domain.JobPlanService;
import in.boss.production.domain.JobStageService;
import in.boss.production.domain.ProductionJobService;
import in.boss.production.domain.ProductionQueryService;
import in.boss.shared.security.BossPermissions;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
@PreAuthorize(BossPermissions.PRODUCTION_VIEW)
public class ProductionJobController {

    private final ProductionQueryService query;
    private final ProductionJobService jobs;
    private final JobStageService stages;
    private final JobMaterialService materials;
    private final JobCostingService costing;
    private final JobInspectionService inspections;
    private final JobPlanService plan;

    public ProductionJobController(ProductionQueryService query, ProductionJobService jobs,
                                   JobStageService stages, JobMaterialService materials,
                                   JobCostingService costing, JobInspectionService inspections,
                                   JobPlanService plan) {
        this.query = query;
        this.jobs = jobs;
        this.stages = stages;
        this.materials = materials;
        this.costing = costing;
        this.inspections = inspections;
        this.plan = plan;
    }

    @GetMapping
    public PageResponse<ProductionJobResponse> list(
            @RequestParam(required = false) UUID cpoId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return query.list(cpoId, status, page, size);
    }

    @GetMapping("/eligible-cpos")
    public List<EligibleCpoLine> eligible() {
        return query.eligibleLines();
    }

    @GetMapping("/routing-templates")
    public List<RoutingTemplateOption> routingTemplates(@RequestParam(required = false) UUID itemId) {
        return query.routingTemplates(itemId);
    }

    @GetMapping("/routing-templates/{routingId}")
    public RoutingTemplateDetail routingTemplate(@PathVariable UUID routingId) {
        return query.routingTemplate(routingId);
    }

    @PostMapping("/routing-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.OWNER_OR_OPS)
    public RoutingTemplateOption createRoutingTemplate(@Valid @RequestBody CreateRoutingTemplateRequest request) {
        return jobs.createRoutingTemplate(request);
    }

    @PostMapping("/routing-templates/{routingId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.OWNER_OR_OPS)
    public RoutingTemplateOption reviseRoutingTemplate(@PathVariable UUID routingId,
            @Valid @RequestBody ReviseRoutingTemplateRequest request) {
        return jobs.reviseRoutingTemplate(routingId, request);
    }

    @GetMapping("/features")
    public Map<String, Boolean> features() {
        return query.features();
    }

    @GetMapping("/metrics")
    public BusinessMetrics metrics() {
        return query.businessMetrics();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.PRODUCTION_WRITE)
    public ProductionJobResponse create(@Valid @RequestBody CreateJobRequest request) {
        return jobs.create(request);
    }

    @GetMapping("/{jobId}")
    public ProductionJobResponse get(@PathVariable UUID jobId) {
        return query.get(jobId);
    }

    @GetMapping("/routing-plan-preview")
    public PlanPreviewResponse routingPlanPreview(
            @RequestParam UUID routingTemplateId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deliveryDate) {
        return plan.previewForTemplate(routingTemplateId, deliveryDate);
    }

    @GetMapping("/{jobId}/plan-preview")
    public PlanPreviewResponse planPreview(
            @PathVariable UUID jobId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deliveryDate) {
        return plan.preview(jobId, deliveryDate);
    }

    @PostMapping("/{jobId}/plan")
    @PreAuthorize(BossPermissions.PRODUCTION_WRITE)
    public ProductionJobResponse savePlan(@PathVariable UUID jobId,
                                          @Valid @RequestBody SavePlanRequest request) {
        return plan.save(jobId, request);
    }

    @PostMapping("/{jobId}/hold")
    @PreAuthorize(BossPermissions.PRODUCTION_WRITE)
    public ProductionJobResponse hold(@PathVariable UUID jobId, @Valid @RequestBody ReasonRequest request) {
        return jobs.hold(jobId, request);
    }

    @PostMapping("/{jobId}/release-hold")
    @PreAuthorize(BossPermissions.PRODUCTION_WRITE)
    public ProductionJobResponse releaseHold(@PathVariable UUID jobId) {
        return jobs.releaseHold(jobId);
    }

    @PostMapping("/{jobId}/complete")
    @PreAuthorize(BossPermissions.PRODUCTION_COMPLETE)
    public ProductionJobResponse finish(@PathVariable UUID jobId) {
        return stages.finish(jobId);
    }

    @PostMapping("/{jobId}/cancel")
    @PreAuthorize(BossPermissions.OWNER_OR_OPS)
    public ProductionJobResponse cancel(@PathVariable UUID jobId, @Valid @RequestBody ReasonRequest request) {
        return jobs.cancel(jobId, request);
    }

    @PostMapping("/{jobId}/stages/{stageId}/start")
    @PreAuthorize(BossPermissions.PRODUCTION_EXECUTE)
    public ProductionJobResponse start(@PathVariable UUID jobId, @PathVariable UUID stageId,
                                       @RequestBody(required = false) StageActionRequest request) {
        return stages.start(jobId, stageId, request);
    }

    @PostMapping("/{jobId}/stages/{stageId}/complete")
    @PreAuthorize(BossPermissions.PRODUCTION_COMPLETE)
    public ProductionJobResponse complete(@PathVariable UUID jobId, @PathVariable UUID stageId,
                                          @RequestBody(required = false) StageActionRequest request) {
        return stages.complete(jobId, stageId, request);
    }

    @PostMapping("/{jobId}/stages/{stageId}/skip")
    @PreAuthorize(BossPermissions.OWNER_OR_OPS)
    public ProductionJobResponse skip(@PathVariable UUID jobId, @PathVariable UUID stageId,
                                      @Valid @RequestBody ReasonRequest request) {
        return stages.skip(jobId, stageId, request);
    }

    @PostMapping("/{jobId}/materials/issues")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.PRODUCTION_WRITE)
    public MaterialMovementResponse issue(@PathVariable UUID jobId,
                                          @Valid @RequestBody IssueMaterialRequest request) {
        return materials.issue(jobId, request);
    }

    @PostMapping("/{jobId}/materials/returns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.PRODUCTION_WRITE)
    public MaterialMovementResponse returnMaterial(@PathVariable UUID jobId,
                                                   @Valid @RequestBody ReturnMaterialRequest request) {
        return materials.returnMaterial(jobId, request);
    }

    @GetMapping("/{jobId}/costing")
    @PreAuthorize(BossPermissions.COMMERCIAL_DATA_READ)
    public CostingResponse costing(@PathVariable UUID jobId) {
        return costing.get(jobId);
    }

    @PostMapping("/{jobId}/costing")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.COSTING_WRITE)
    public CostEntryResponse addCost(@PathVariable UUID jobId, @Valid @RequestBody AddCostRequest request) {
        return costing.addEntry(jobId, request);
    }

    @PostMapping("/{jobId}/stages/{stageId}/inspections")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.QUALITY_WRITE)
    public ProductionJobResponse openInspection(@PathVariable UUID jobId, @PathVariable UUID stageId) {
        return inspections.open(jobId, stageId);
    }

    @PostMapping("/{jobId}/inspections/{inspectionId}/result")
    @PreAuthorize(BossPermissions.QUALITY_WRITE)
    public ProductionJobResponse recordInspection(@PathVariable UUID jobId, @PathVariable UUID inspectionId,
                                                  @Valid @RequestBody InspectionResultRequest request) {
        return inspections.record(jobId, inspectionId, request);
    }

    @PostMapping("/{jobId}/inspections/{inspectionId}/concession")
    @PreAuthorize(BossPermissions.CONCESSION_APPROVE)
    public ProductionJobResponse concede(@PathVariable UUID jobId, @PathVariable UUID inspectionId,
                                         @Valid @RequestBody ConcessionRequest request) {
        return inspections.concede(jobId, inspectionId, request);
    }
}
