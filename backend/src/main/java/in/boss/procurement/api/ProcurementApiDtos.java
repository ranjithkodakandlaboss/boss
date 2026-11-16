package in.boss.procurement.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
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
            List<@Valid CreatePrLineRequest> lines) {}

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
            Instant updatedAt) {}

    public record PageResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements) {}
}
