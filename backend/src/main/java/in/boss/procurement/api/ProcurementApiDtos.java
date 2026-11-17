package in.boss.procurement.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ProcurementApiDtos {

    private ProcurementApiDtos() {}

    public record CreatePrLineRequest(
            @NotNull UUID itemId,
            BigDecimal qty,
            BigDecimal quantity,
            @NotNull UUID uomId,
            BigDecimal estUnitPrice,
            UUID storeId,
            LocalDate requiredDate,
            String note,
            String notes) {

        public BigDecimal effectiveQty() {
            BigDecimal value = qty != null ? qty : quantity;
            if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Quantity must be greater than zero");
            }
            return value;
        }

        public String effectiveNotes() {
            return notes != null ? notes : note;
        }
    }

    public record CreatePrRequest(
            String originType,
            UUID jobId,
            UUID replenishmentRequestId,
            UUID siteId,
            UUID prTypeId,
            @Size(max = 200) String prTypeLabel,
            LocalDate requiredDate,
            String priority,
            String notes,
            List<@Valid CreatePrLineRequest> lines,
            UUID customerPoId) {}

    public record UpdatePrRequest(
            UUID siteId,
            UUID prTypeId,
            @Size(max = 200) String prTypeLabel,
            LocalDate requiredDate,
            String priority,
            String notes,
            List<@Valid CreatePrLineRequest> lines) {}

    public record RejectPrRequest(
            @NotBlank(message = "Rejection reason is required") String reason) {}

    public record PrLineResponse(
            UUID id,
            int seq,
            int lineNo,
            UUID itemId,
            String itemCode,
            String itemName,
            BigDecimal qty,
            BigDecimal quantity,
            BigDecimal orderedQty,
            UUID uomId,
            String uomSymbol,
            BigDecimal estUnitPrice,
            UUID storeId,
            LocalDate requiredDate,
            String note,
            String notes) {}

    public record PurchaseRequisitionResponse(
            UUID id,
            String prNumber,
            UUID siteId,
            String originType,
            UUID jobId,
            String status,
            String priority,
            UUID prTypeId,
            String prTypeLabel,
            LocalDate requiredDate,
            String notes,
            UUID requestedBy,
            UUID approvedBy,
            Instant approvedAt,
            String rejectionReason,
            UUID rejectedBy,
            Instant rejectedAt,
            List<PrLineResponse> lines,
            BigDecimal totalQty,
            BigDecimal totalOrderedQty,
            Instant createdAt,
            Instant updatedAt,
            UUID customerPoId) {}

    public record PageResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements) {}

    public record CreateSupplierQuotationRequest(
            @NotNull UUID supplierId) {}

    public record SupplierQuotationLineRequest(
            @NotNull UUID prLineId,
            @NotNull @DecimalMin("0.00") BigDecimal unitPrice,
            @Min(0) Integer leadTimeDays,
            @DecimalMin("0.00") BigDecimal taxRate) {}

    public record ReceiveSupplierQuotationRequest(
            LocalDate validUntil,
            @NotEmpty List<@Valid SupplierQuotationLineRequest> lines,
            @Size(max = 80) String supplierQuoteRef,
            @Min(0) Integer paymentTerms,
            @DecimalMin("0.00") BigDecimal freightAmount,
            @DecimalMin("0.00") BigDecimal discountAmount,
            @DecimalMin("0.00") BigDecimal taxAmount) {}

    public record SelectSupplierQuotationRequest(
            @Size(max = 1000) String reason) {}

    public record SupplierQuotationLineResponse(
            UUID id,
            UUID prLineId,
            UUID itemId,
            BigDecimal unitPrice,
            Integer leadTimeDays,
            boolean isSelected,
            BigDecimal taxRate) {}

    public record SupplierQuotationResponse(
            UUID id,
            UUID prId,
            UUID supplierId,
            String supplierName,
            String status,
            LocalDate validUntil,
            List<SupplierQuotationLineResponse> lines,
            String supplierQuoteRef,
            Integer paymentTerms,
            BigDecimal freightAmount,
            BigDecimal discountAmount,
            BigDecimal taxAmount,
            String selectionReason,
            String deliveryStatus,
            String deliveryRecipient,
            String deliveryError) {}

    public record SupplierQuotationComparisonOffer(
            UUID supplierQuotationId,
            UUID supplierId,
            String supplierName,
            String status,
            BigDecimal unitPrice,
            Integer leadTimeDays,
            LocalDate validUntil,
            boolean isSelected) {}

    public record SupplierQuotationComparisonLine(
            UUID prLineId,
            UUID itemId,
            String itemCode,
            String itemName,
            BigDecimal quantity,
            UUID uomId,
            String uomSymbol,
            List<SupplierQuotationComparisonOffer> offers) {}

    public record SupplierQuotationComparisonResponse(
            UUID prId,
            List<SupplierQuotationComparisonLine> lines) {}

    public record CreateSpoLineRequest(
            @NotNull UUID prLineId,
            @NotNull UUID itemId,
            @NotNull @DecimalMin("0.001") BigDecimal orderedQty,
            @NotNull @DecimalMin("0.00") BigDecimal unitPrice,
            @NotNull UUID uomId,
            LocalDate expectedDeliveryDate) {}

    public record CreateSpoRequest(
            @NotNull UUID supplierId,
            @NotNull UUID prId,
            @NotNull UUID supplierQuotationId,
            @NotEmpty List<@Valid CreateSpoLineRequest> lines) {}

    public record SpoLineResponse(
            UUID id,
            UUID itemId,
            String itemCode,
            String itemName,
            BigDecimal orderedQty,
            BigDecimal receivedQty,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            LocalDate expectedDeliveryDate) {}

    public record SpoResponse(
            UUID id,
            String spoNumber,
            UUID prId,
            UUID supplierQuotationId,
            UUID supplierId,
            String supplierName,
            String status,
            List<SpoLineResponse> lines,
            BigDecimal totalAmount,
            Instant createdAt,
            Integer revision,
            String declineReason) {}

    public record DeclineSpoRequest(
            @NotBlank @Size(max = 1000) String reason) {}

    public record AmendSpoRequest(
            @NotBlank @Size(max = 80) String reason,
            @NotBlank @Size(max = 2000) String changeNote,
            UUID lineId,
            @DecimalMin("0.001") BigDecimal quantity,
            @DecimalMin("0.00") BigDecimal unitPrice,
            LocalDate expectedDate,
            @Min(0) Integer paymentTerms) {}

    public record SpoAmendmentResponse(
            UUID id,
            UUID spoId,
            int revision,
            String reason,
            String changeNote,
            String status) {}

    public record CreateGrnLotRequest(
            @NotBlank @Size(max = 80) String lotNumber,
            @NotNull @DecimalMin("0.001") BigDecimal qty,
            LocalDate expiryDate,
            @Size(max = 80) String supplierLotRef) {}

    public record CreateGrnLineRequest(
            @NotNull UUID spoLineId,
            @NotNull @DecimalMin("0.001") BigDecimal receivedQty,
            @DecimalMin("0.00") BigDecimal rejectedQty,
            @NotEmpty List<@Valid CreateGrnLotRequest> lots,
            String notes) {}

    public record CreateGrnRequest(
            @NotNull UUID spoId,
            @NotNull LocalDate receivedDate,
            @NotBlank @Size(max = 80) String deliveryChallan,
            @Size(max = 40) String vehicleNumber,
            @NotNull UUID storeId,
            String notes,
            @NotEmpty List<@Valid CreateGrnLineRequest> lines) {}

    public record GrnLineResponse(
            UUID id,
            UUID spoLineId,
            UUID itemId,
            String itemCode,
            String itemName,
            BigDecimal receivedQty,
            BigDecimal acceptedQty,
            BigDecimal rejectedQty,
            String uomSymbol,
            List<UUID> lotIds,
            List<String> lotNumbers,
            List<String> lotStatuses,
            List<BigDecimal> lotQuantities) {}

    public record GrnResponse(
            UUID id,
            String grnNumber,
            UUID spoId,
            String spoNumber,
            UUID supplierId,
            String supplierName,
            UUID siteId,
            String status,
            LocalDate receivedDate,
            String supplierDcRef,
            String vehicleNumber,
            List<GrnLineResponse> lines) {}

    public record CreateSupplierReturnLineRequest(
            @NotNull UUID grnLineId,
            @NotNull UUID lotId,
            @NotNull @DecimalMin("0.001") BigDecimal quantity,
            @Size(max = 500) String reason) {}

    public record CreateSupplierReturnRequest(
            @NotNull UUID grnId,
            @NotBlank @Size(max = 1000) String reason,
            @NotEmpty List<@Valid CreateSupplierReturnLineRequest> lines) {}

    public record SupplierReturnResponse(
            UUID id,
            String returnNumber,
            UUID grnId,
            UUID spoId,
            UUID supplierId,
            String status,
            String reason) {}

    public record SupplierPerformanceResponse(
            UUID supplierId,
            String supplierName,
            int totalReceipts,
            BigDecimal onTimeDeliveryPct,
            BigDecimal qualityRejectionPct,
            BigDecimal avgLeadTimeDays) {}

    public record InspectionResponse(
            UUID id,
            String inspectionNumber,
            UUID grnId,
            UUID lotId,
            UUID itemId,
            BigDecimal quantityInspected,
            BigDecimal quantityAccepted,
            BigDecimal quantityRejected,
            String result,
            String status,
            String notes) {}

    public record CompleteInspectionRequest(
            @NotNull @DecimalMin("0.000") BigDecimal acceptedQty,
            @NotNull @DecimalMin("0.000") BigDecimal rejectedQty,
            boolean hold,
            @Size(max = 2000) String notes) {}

    public record AmendmentFileResponse(
            UUID id,
            UUID amendmentId,
            String fileName,
            String contentType,
            int sizeBytes) {}
}
