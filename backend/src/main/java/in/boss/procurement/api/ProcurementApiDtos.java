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
            @Min(0) Integer leadTimeDays) {}

    public record ReceiveSupplierQuotationRequest(
            LocalDate validUntil,
            @NotEmpty List<@Valid SupplierQuotationLineRequest> lines) {}

    public record SupplierQuotationLineResponse(
            UUID id,
            UUID prLineId,
            UUID itemId,
            BigDecimal unitPrice,
            Integer leadTimeDays,
            boolean isSelected) {}

    public record SupplierQuotationResponse(
            UUID id,
            UUID prId,
            UUID supplierId,
            String supplierName,
            String status,
            LocalDate validUntil,
            List<SupplierQuotationLineResponse> lines) {}

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
            Instant createdAt) {}
}
