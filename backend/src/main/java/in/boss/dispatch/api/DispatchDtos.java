package in.boss.dispatch.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class DispatchDtos {

    private DispatchDtos() {}

    public record DispatchSummary(
            UUID id,
            String dispatchNumber,
            UUID customerId,
            String customerName,
            UUID cpoId,
            String cpoNumber,
            UUID jobId,
            String jobNumber,
            List<String> jobs,
            UUID sourceSiteId,
            String sourceSiteName,
            UUID sourceStoreId,
            String sourceStoreName,
            String carrier,
            UUID dispatchMethodOptionId,
            String dispatchMethodLabel,
            String vehicleNumber,
            String driverName,
            String lrNumber,
            LocalDate dispatchDate,
            LocalDate expectedDelivery,
            BigDecimal totalQty,
            Integer lineCount,
            String status,
            boolean hasPoD,
            Instant createdAt
    ) {}

    public record DispatchDetail(
            UUID id,
            String dispatchNumber,
            UUID customerId,
            String customerName,
            UUID cpoId,
            String cpoNumber,
            UUID jobId,
            String jobNumber,
            List<String> jobs,
            UUID sourceSiteId,
            String sourceSiteName,
            UUID sourceStoreId,
            String sourceStoreName,
            String destination,
            String carrier,
            UUID dispatchMethodOptionId,
            String dispatchMethodLabel,
            String vehicleNumber,
            String driverName,
            String lrNumber,
            LocalDate dispatchDate,
            LocalDate expectedDelivery,
            LocalDate actualDeliveryDate,
            String deliveryCondition,
            String podReference,
            String deliveryNotes,
            String receivedByName,
            Instant receivedAt,
            boolean hasPoD,
            String status,
            BigDecimal totalQty,
            Integer lineCount,
            UUID qualityReleaseId,
            String qualityReleaseStatus,
            String qualityReleaseBy,
            UUID qualityInspectionId,
            String qualityInspectionNumber,
            String qualityInspectionResult,
            UUID failedInspectionId,
            String failedInspectionNumber,
            UUID linkedNcrId,
            String linkedNcrNumber,
            String challanNumber,
            String challanGcsPath,
            List<DispatchLineDto> lines,
            List<DispatchPackageDto> packages,
            List<DispatchHistoryDto> history,
            List<LinkedInvoiceDto> linkedInvoices,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record DispatchLineDto(
            UUID id,
            UUID dispatchId,
            UUID cpoLineId,
            UUID jobId,
            String jobNumber,
            UUID itemId,
            String itemCode,
            String itemName,
            UUID lotId,
            String lotNumber,
            BigDecimal quantity,
            UUID uomId,
            String uomSymbol,
            UUID packingSpecId,
            UUID clearanceCertId,
            Integer lineNo
    ) {}

    public record DispatchPackageDto(
            UUID id,
            UUID dispatchId,
            String packageCode,
            String packageType,
            String productDescription,
            BigDecimal quantity,
            String uom,
            BigDecimal weightKg,
            BigDecimal lengthMm,
            BigDecimal widthMm,
            BigDecimal heightMm,
            String packingNotes,
            Instant createdAt
    ) {}

    public record DispatchHistoryDto(
            UUID id,
            String action,
            String title,
            String description,
            String actorName,
            Instant timestamp
    ) {}

    public record LinkedInvoiceDto(
            UUID id,
            String invoiceNumber,
            String status,
            BigDecimal totalAmount,
            LocalDate invoiceDate
    ) {}

    public record CreateDispatchRequest(
            UUID customerId,
            String customerName,
            UUID cpoId,
            UUID jobId,
            UUID sourceSiteId,
            String sourceSiteName,
            UUID sourceStoreId,
            String sourceStoreName,
            String carrier,
            String vehicleNumber,
            String driverName,
            String lrNumber,
            LocalDate dispatchDate,
            LocalDate expectedDelivery,
            String destination,
            String notes,
            List<String> jobs,
            List<CreateDispatchLineRequest> lines,
            List<CreateDispatchPackageRequest> packages,
            UUID dispatchMethodOptionId
    ) {}

    public record CreateDispatchLineRequest(
            UUID cpoLineId,
            UUID jobId,
            UUID itemId,
            BigDecimal quantity,
            UUID uomId,
            UUID lotId,
            UUID packingSpecId,
            UUID clearanceCertId
    ) {}

    public record CreateDispatchPackageRequest(
            String packageCode,
            String packageType,
            String productDescription,
            BigDecimal quantity,
            String uom,
            BigDecimal weightKg,
            BigDecimal lengthMm,
            BigDecimal widthMm,
            BigDecimal heightMm,
            String packingNotes
    ) {}

    public record UpdateStatusRequest(
            String status,
            String notes
    ) {}

    public record RecordPodRequest(
            String receivedBy,
            LocalDate deliveredDate,
            String condition,
            String podReference,
            String notes,
            String signatureRef
    ) {}

    public record Dimensions(
            BigDecimal length,
            BigDecimal width,
            BigDecimal height,
            String unit
    ) {}

    public record PackingSpecDto(
            UUID id,
            UUID itemId,
            String itemCode,
            String itemName,
            UUID packingTypeId,
            String packingTypeLabel,
            Integer unitsPerPack,
            BigDecimal packWeight,
            String packWeightUom,
            Dimensions packDimensions,
            String labelInstructions,
            String handlingInstructions,
            boolean isActive,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record CreatePackingSpecRequest(
            UUID itemId,
            UUID packingTypeId,
            String packingTypeLabel,
            Integer unitsPerPack,
            BigDecimal packWeight,
            String packWeightUom,
            Dimensions packDimensions,
            String labelInstructions,
            String handlingInstructions
    ) {}

    public record RecordPackingRequest(
            UUID packingSpecId,
            UUID packingTypeId,
            String packingTypeLabel,
            Integer actualPacksCount,
            BigDecimal actualGrossWeight,
            String packingNote
    ) {}

    public record DispatchPackingRecordDto(
            UUID id,
            UUID dispatchId,
            UUID packingSpecId,
            UUID packingTypeId,
            String packingTypeLabel,
            Integer actualPacksCount,
            BigDecimal actualGrossWeight,
            String packingNote,
            Instant recordedAt
    ) {}

    public record PagedPackingSpecs(
            List<PackingSpecDto> content,
            long totalElements,
            int totalPages,
            int page,
            int size
    ) {}
}
