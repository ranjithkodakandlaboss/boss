package in.boss.production.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ProductionApiDtos {

    private ProductionApiDtos() {}

    public record CreateJobRequest(
            // Mode A — from Customer PO (cpoId + cpoLineId required together)
            UUID cpoId,
            UUID cpoLineId,
            // Mode B — standalone (customerId + itemId + qty + siteId required together)
            UUID customerId,
            UUID itemId,
            @DecimalMin("0.001") BigDecimal qty,
            UUID siteId,
            String description,
            // common
            @NotNull UUID routingTemplateId,
            LocalDate scheduledStart,
            LocalDate scheduledEnd,
            @Pattern(regexp = "low|normal|high|urgent") String priority,
            @DecimalMin("0.001") BigDecimal quantity
    ) {
        /** Legacy 6-arg compact constructor */
        public CreateJobRequest(UUID cpoId, UUID cpoLineId, UUID routingTemplateId,
                                 LocalDate scheduledStart, LocalDate scheduledEnd, String priority) {
            this(cpoId, cpoLineId, null, null, null, null, null,
                 routingTemplateId, scheduledStart, scheduledEnd, priority, null);
        }
        /** Legacy 7-arg compact constructor (adds explicit quantity) */
        public CreateJobRequest(UUID cpoId, UUID cpoLineId, UUID routingTemplateId,
                                 LocalDate scheduledStart, LocalDate scheduledEnd,
                                 String priority, BigDecimal quantity) {
            this(cpoId, cpoLineId, null, null, null, null, null,
                 routingTemplateId, scheduledStart, scheduledEnd, priority, quantity);
        }
        public boolean isStandalone() {
            return cpoId == null && cpoLineId == null;
        }
    }

    public record StagePlanEntry(
            @NotNull UUID stageId,
            @NotNull LocalDate plannedStart,
            @NotNull LocalDate plannedEnd
    ) {}

    public record SavePlanRequest(
            @NotNull @NotEmpty List<@jakarta.validation.Valid StagePlanEntry> stages
    ) {}

    public record PlanPreviewStage(
            UUID stageId,
            String stageName,
            int seqNo,
            int estimatedDurationMin,
            LocalDate plannedStart,
            LocalDate plannedEnd
    ) {}

    public record PlanPreviewResponse(List<PlanPreviewStage> stages) {}

    public record ReasonRequest(@NotBlank String reason) {}

    public record StageActionRequest(String note) {}

    public record IssueMaterialRequest(
            @NotNull UUID requirementId,
            @NotNull UUID lotId,
            @NotNull @DecimalMin("0.001") BigDecimal qty,
            String note
    ) {}

    public record ReturnMaterialRequest(
            @NotNull UUID sourceIssueId,
            @NotNull @DecimalMin("0.001") BigDecimal qty,
            String note
    ) {}

    public record AddCostRequest(
            @NotBlank @Pattern(regexp = "LABOUR|OVERHEAD|OTHER") String entryType,
            @NotBlank String description,
            @NotNull @DecimalMin("0.00") BigDecimal amount,
            BigDecimal qty,
            BigDecimal unitCost
    ) {}

    public record InspectionResultRequest(
            @NotBlank @Pattern(regexp = "pass|fail") String result,
            BigDecimal quantityAccepted,
            BigDecimal quantityRejected,
            String note
    ) {}

    public record ConcessionRequest(
            @NotBlank @Pattern(regexp = "use_as_is|rework|conditional") String concessionType,
            @NotBlank String note
    ) {}

    public record JobStageResponse(
            UUID id,
            int seqNo,
            String stageName,
            String stageCode,
            UUID stageConfigOptionId,
            String status,
            boolean requiresInspection,
            String inspectionStatus,
            boolean isOptional,
            boolean canSkip,
            String workCentre,
            Instant actualStartAt,
            Instant actualEndAt,
            UUID completedBy,
            UUID assetId,
            String assetCode,
            String blockerReason,
            // plan columns (null when no plan has been set)
            LocalDate plannedStart,
            LocalDate plannedEnd,
            Integer estimatedDurationMin,
            BigDecimal completedQty,
            BigDecimal blockedQty
    ) {
        /** Legacy compact constructor — keeps existing callers compiling */
        public JobStageResponse(UUID id, int seqNo, String stageName, String stageCode,
                UUID stageConfigOptionId, String status, boolean requiresInspection,
                String inspectionStatus, boolean isOptional, boolean canSkip, String workCentre,
                Instant actualStartAt, Instant actualEndAt, UUID completedBy, UUID assetId,
                String assetCode, String blockerReason) {
            this(id, seqNo, stageName, stageCode, stageConfigOptionId, status, requiresInspection,
                 inspectionStatus, isOptional, canSkip, workCentre, actualStartAt, actualEndAt,
                 completedBy, assetId, assetCode, blockerReason, null, null, null, null, null);
        }
    }

    public record MaterialRequirementResponse(
            UUID id,
            UUID itemId,
            String itemCode,
            String itemName,
            BigDecimal requiredQty,
            BigDecimal issuedQty,
            BigDecimal returnedQty,
            BigDecimal netIssuedQty,
            String uomSymbol,
            String readiness
    ) {}

    public record MaterialMovementResponse(
            UUID id,
            UUID movementId,
            UUID requirementId,
            UUID sourceIssueId,
            UUID lotId,
            String issueType,
            BigDecimal quantity,
            BigDecimal unitCost,
            Instant createdAt
    ) {}

    public record InspectionResponse(
            UUID id,
            UUID stageId,
            String inspectionNumber,
            String inspectionType,
            String status,
            String result,
            BigDecimal quantityInspected,
            BigDecimal quantityAccepted,
            BigDecimal quantityRejected,
            UUID ncrId,
            String ncrStatus,
            String concessionStatus
    ) {}

    public record CostEntryResponse(
            UUID id,
            String entryType,
            String description,
            BigDecimal qty,
            BigDecimal unitCost,
            BigDecimal amount,
            boolean estimated,
            Instant createdAt
    ) {}

    public record CostSummary(
            BigDecimal materialCost,
            BigDecimal labourCost,
            BigDecimal overheadCost,
            BigDecimal totalCost,
            BigDecimal sellingPrice,
            BigDecimal marginPct,
            String materialStatus,
            String labourStatus,
            String overheadStatus
    ) {}

    public record HistoricalCostReference(
            int completedOrderCount,
            BigDecimal totalQuantity,
            BigDecimal totalActualCost,
            BigDecimal weightedAverageUnitCost,
            String basis
    ) {}

    public record CostingResponse(
            UUID jobId,
            String jobNumber,
            CostSummary estimatedCost,
            CostSummary actualCost,
            BigDecimal variance,
            BigDecimal estimatedProfit,
            BigDecimal actualProfit,
            String estimatedCostBasis,
            HistoricalCostReference historicalReference,
            List<CostEntryResponse> entries
    ) {}

    public record ProductionJobResponse(
            UUID id,
            String jobNumber,
            UUID cpoId,
            UUID cpoLineId,
            String cpoNumber,
            UUID customerId,
            String customerName,
            UUID itemId,
            String itemCode,
            String itemName,
            BigDecimal qty,
            String uomSymbol,
            UUID routingTemplateId,
            String routingTemplateName,
            String routingTemplateVersion,
            String status,
            String holdReason,
            String priority,
            UUID siteId,
            String siteName,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            Instant actualStart,
            Instant actualEnd,
            String currentStage,
            String materialReadiness,
            String risk,
            Long daysRemaining,
            LocalDate projectedCompletion,
            String projectionBasis,
            List<JobStageResponse> stages,
            List<MaterialRequirementResponse> materials,
            List<MaterialMovementResponse> movements,
            List<InspectionResponse> inspections,
            CostSummary estimatedCost,
            CostSummary actualCost,
            BigDecimal variance,
            Instant createdAt
    ) {}

    public record EligibleCpoLine(
            UUID cpoId,
            String cpoNumber,
            UUID cpoLineId,
            int lineNo,
            UUID customerId,
            String customerName,
            UUID itemId,
            String itemCode,
            String itemName,
            BigDecimal qty,
            BigDecimal unitPrice,
            BigDecimal sellingPrice,
            UUID siteId,
            String siteName,
            LocalDate requiredBy,
            String status,
            BigDecimal remainingQty
    ) {}

    public record RoutingTemplateOption(
            UUID id,
            String name,
            String version,
            UUID finishedItemId,
            int stageCount
    ) {}

    public record RoutingTemplateDetail(
            UUID id, String name, String version, UUID finishedItemId, int stageCount,
            List<RoutingStageRequest> stages
    ) {}

    public record RoutingStageRequest(
            @NotBlank String name,
            String workCentre,
            UUID assetId,
            UUID stageConfigOptionId,
            UUID inspectionTemplateId,
            Boolean requiresInspection,
            Boolean isOptional,
            @Pattern(regexp = "production|inspection|rework|hold") String stageType,
            @Min(0) Integer setupTimeMin,
            @Min(0) Integer runTimeMin
    ) {
        public RoutingStageRequest(String name, String workCentre, UUID assetId, UUID stageConfigOptionId) {
            this(name, workCentre, assetId, stageConfigOptionId, null);
        }

        public RoutingStageRequest(String name, String workCentre, UUID assetId, UUID stageConfigOptionId,
                                   UUID inspectionTemplateId) {
            this(name, workCentre, assetId, stageConfigOptionId, inspectionTemplateId, null, null, null, null, null);
        }
    }

    public record CreateRoutingTemplateRequest(
            @NotBlank String name,
            String stageName,
            java.util.List<@jakarta.validation.Valid RoutingStageRequest> stages,
            UUID finishedItemId
    ) {
        public CreateRoutingTemplateRequest(String name, String stageName) {
            this(name, stageName, null, null);
        }

        public CreateRoutingTemplateRequest(String name, String stageName, List<RoutingStageRequest> stages) {
            this(name, stageName, stages, null);
        }
    }

    public record ReviseRoutingTemplateRequest(
            @NotEmpty List<@jakarta.validation.Valid RoutingStageRequest> stages
    ) {}

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements) {}

    public record BusinessMetrics(
            long activeJobs,
            long completedJobs,
            long delayedJobs,
            Map<String, Long> byStatus,
            Map<String, Long> byCurrentStage,
            BigDecimal materialIssuedQty,
            BigDecimal materialReturnedQty,
            BigDecimal materialConsumedQty,
            BigDecimal estimatedCost,
            BigDecimal actualCost,
            BigDecimal costVariance
    ) {}
}
