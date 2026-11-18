package in.boss.production.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ProductionApiDtos {

    private ProductionApiDtos() {}

    public record CreateJobRequest(
            @NotNull UUID cpoId,
            @NotNull UUID cpoLineId,
            @NotNull UUID routingTemplateId,
            LocalDate scheduledStart,
            LocalDate scheduledEnd,
            @Pattern(regexp = "low|normal|high|urgent") String priority,
            @DecimalMin("0.001") BigDecimal quantity
    ) {
        public CreateJobRequest(UUID cpoId, UUID cpoLineId, UUID routingTemplateId,
                                 LocalDate scheduledStart, LocalDate scheduledEnd, String priority) {
            this(cpoId, cpoLineId, routingTemplateId, scheduledStart, scheduledEnd, priority, null);
        }
    }

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
            String assetCode
    ) {}

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
            BigDecimal marginPct
    ) {}

    public record CostingResponse(
            UUID jobId,
            String jobNumber,
            CostSummary estimatedCost,
            CostSummary actualCost,
            BigDecimal variance,
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

    public record RoutingStageRequest(
            @NotBlank String name,
            String workCentre,
            UUID assetId,
            UUID stageConfigOptionId
    ) {}

    public record CreateRoutingTemplateRequest(
            @NotBlank String name,
            String stageName,
            java.util.List<@jakarta.validation.Valid RoutingStageRequest> stages
    ) {
        public CreateRoutingTemplateRequest(String name, String stageName) {
            this(name, stageName, null);
        }
    }

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
