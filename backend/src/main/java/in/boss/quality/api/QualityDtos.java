package in.boss.quality.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class QualityDtos {

    private QualityDtos() {}

    // ---- Inspection ----

    public record InspectionSummary(
            UUID id,
            String inspectionNumber,
            String inspectionType,
            UUID inspectionTypeOptionId,
            String inspectionTypeLabel,
            String referenceType,
            UUID referenceId,
            String referenceLabel,
            UUID itemId,
            String itemCode,
            String itemName,
            UUID lotId,
            BigDecimal quantityInspected,
            BigDecimal quantityAccepted,
            BigDecimal quantityRejected,
            String result,
            String status,
            UUID inspectorId,
            String inspectorName,
            LocalDate inspectionDate,
            String notes,
            UUID templateId,
            String templateName,
            Instant createdAt
    ) {}

    public record InspectionDetail(
            UUID id,
            String inspectionNumber,
            String inspectionType,
            UUID inspectionTypeOptionId,
            String inspectionTypeLabel,
            String referenceType,
            UUID referenceId,
            String referenceLabel,
            UUID itemId,
            String itemCode,
            String itemName,
            UUID lotId,
            BigDecimal quantityInspected,
            BigDecimal quantityAccepted,
            BigDecimal quantityRejected,
            String result,
            String status,
            UUID inspectorId,
            String inspectorName,
            LocalDate inspectionDate,
            String notes,
            UUID templateId,
            String templateName,
            List<ChecklistResponseDto> checklistResponses,
            List<NcrSummary> linkedNcrs,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record ChecklistResponseDto(
            UUID id,
            UUID checklistItemId,
            String itemDescription,
            String responseType,
            boolean isMandatory,
            String responseValue,
            Boolean responsePass,
            String observation,
            boolean answered
    ) {}

    public record CreateInspectionRequest(
            String inspectionType,
            String referenceType,
            UUID referenceId,
            UUID itemId,
            UUID lotId,
            BigDecimal quantityInspected,
            UUID templateId,
            UUID inspectorId,
            LocalDate inspectionDate,
            String notes,
            UUID inspectionTypeOptionId
    ) {
        public CreateInspectionRequest(
                String inspectionType,
                String referenceType,
                UUID referenceId,
                UUID itemId,
                UUID lotId,
                BigDecimal quantityInspected,
                UUID templateId,
                UUID inspectorId,
                LocalDate inspectionDate,
                String notes
        ) {
            this(inspectionType, referenceType, referenceId, itemId, lotId, quantityInspected,
                    templateId, inspectorId, inspectionDate, notes, null);
        }
    }

    public record RecordResultRequest(
            String result,
            BigDecimal quantityAccepted,
            BigDecimal quantityRejected,
            List<ChecklistResponseInput> checklistResponses,
            String notes
    ) {}

    public record ChecklistResponseInput(
            UUID checklistItemId,
            String responseValue,
            Boolean responsePass,
            String observation
    ) {}

    // ---- NCR ----

    public record NcrSummary(
            UUID id,
            String ncrNumber,
            UUID inspectionId,
            UUID itemId,
            String itemCode,
            String itemName,
            UUID lotId,
            UUID jobId,
            UUID defectCategoryId,
            String defectCategoryLabel,
            String defectDescription,
            String severity,
            BigDecimal quantity,
            String status,
            String disposition,
            UUID raisedBy,
            String raisedByName,
            String concessionStatus,
            Instant createdAt
    ) {}

    public record NcrDetail(
            UUID id,
            String ncrNumber,
            UUID inspectionId,
            String inspectionNumber,
            UUID itemId,
            String itemCode,
            String itemName,
            UUID lotId,
            UUID jobId,
            String jobNumber,
            UUID defectCategoryId,
            String defectCategoryLabel,
            String defectDescription,
            String severity,
            BigDecimal quantity,
            String rootCause,
            String status,
            String disposition,
            String resolution,
            UUID raisedBy,
            String raisedByName,
            UUID resolvedBy,
            String resolvedByName,
            Instant resolvedAt,
            List<ConcessionDto> concessions,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record CreateNcrRequest(
            UUID inspectionId,
            UUID itemId,
            UUID lotId,
            UUID jobId,
            UUID defectCategoryId,
            String defectDescription,
            String severity,
            BigDecimal quantity,
            String rootCause
    ) {}

    public record UpdateNcrRequest(
            String status,
            String disposition,
            String rootCause,
            String resolution
    ) {}

    // ---- Concession ----

    public record ConcessionDto(
            UUID id,
            UUID ncrId,
            String justification,
            UUID approvedBy,
            String approvedByName,
            String approvalNotes,
            String status,
            Instant createdAt
    ) {}

    public record CreateConcessionRequest(
            String justification
    ) {}

    // ---- Clearance Certificate ----

    public record ClearanceCertificateDto(
            UUID id,
            String certNumber,
            UUID jobId,
            String jobNumber,
            UUID customerId,
            String customerName,
            String status,
            Instant issuedAt,
            UUID issuedBy,
            String issuedByName,
            String issuedByCustomerContact,
            String customerRef,
            String notes,
            Instant createdAt
    ) {}

    public record CreateClearanceCertificateRequest(
            UUID jobId,
            String issuedByCustomerContact,
            String customerRef,
            String notes
    ) {}

    // ---- Dispatch Eligibility ----

    public record DispatchEligibilityResponse(
            UUID jobId,
            boolean eligible,
            String reason,
            List<String> blockers,
            String inspectionStatus,
            String clearanceCertificateStatus,
            UUID clearanceCertificateId
    ) {}

    // ---- Checklist Template (Admin) ----

    public record ChecklistTemplateDto(
            UUID id,
            String name,
            String description,
            String scope,
            String inspectionType,
            boolean isActive,
            List<ChecklistItemDto> items,
            Instant createdAt
    ) {}

    public record ChecklistItemDto(
            UUID id,
            int sortOrder,
            String description,
            String responseType,
            boolean isMandatory,
            String unit,
            BigDecimal specMin,
            BigDecimal specMax
    ) {}

    public record CreateChecklistTemplateRequest(
            String name,
            String description,
            String scope,
            String inspectionType,
            List<ChecklistItemInput> items
    ) {}

    public record UpdateChecklistTemplateRequest(
            String name,
            String description,
            String scope,
            String inspectionType,
            Boolean isActive,
            List<ChecklistItemInput> items
    ) {}

    public record ChecklistItemInput(
            UUID id,
            int sortOrder,
            String description,
            String responseType,
            boolean isMandatory,
            String unit,
            BigDecimal specMin,
            BigDecimal specMax
    ) {}
}
