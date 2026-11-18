package in.boss.inventory.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class InventoryApiDtos {

    private InventoryApiDtos() {}

    public record CreateLotRequest(
            @NotNull UUID itemId,
            @NotNull UUID storeId,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qty,
            @DecimalMin(value = "0.0000") BigDecimal unitCost,
            String batchRef,
            LocalDate expiryDate,
            String status,
            UUID grnLineId
    ) {}

    public record AddMovementRequest(
            @NotBlank String movementType,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qty,
            String referenceType,
            UUID referenceId,
            String note
    ) {}

    public record ReservationRequirement(
            @NotNull UUID itemId,
            @NotNull UUID storeId,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qty
    ) {}

    public record ReservationRequest(
            @NotNull UUID jobId,
            @NotEmpty List<@Valid ReservationRequirement> requirements
    ) {}

    public record ReservationAllocation(UUID lotId, BigDecimal qty) {}

    public record ReservationLineResponse(
            UUID itemId,
            UUID storeId,
            BigDecimal requested,
            BigDecimal reserved,
            BigDecimal shortfall,
            List<ReservationAllocation> allocations
    ) {}

    public record ReservationResponse(
            UUID jobId,
            String status,
            List<ReservationLineResponse> lines
    ) {}

    public record CustomerPoReservationRequirement(
            @NotNull UUID cpoLineId,
            @NotNull UUID storeId,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qty
    ) {}

    public record CustomerPoReservationRequest(
            @NotNull UUID cpoId,
            @NotEmpty List<@Valid CustomerPoReservationRequirement> lines
    ) {}

    public record CustomerPoReservationLineResponse(
            UUID cpoLineId,
            UUID itemId,
            UUID storeId,
            BigDecimal requested,
            BigDecimal reserved,
            BigDecimal shortfall,
            List<ReservationAllocation> allocations
    ) {}

    public record CustomerPoReservationResponse(
            UUID cpoId,
            String status,
            List<CustomerPoReservationLineResponse> lines
    ) {}

    public record IssueMaterialRequest(
            @NotNull UUID jobId,
            @NotNull UUID lotId,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qty,
            String note
    ) {}

    public record ReturnMaterialRequest(
            @NotNull UUID jobId,
            @NotNull UUID lotId,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qty,
            String returnReason
    ) {}

    public record MaterialLotResponse(
            UUID id,
            UUID itemId,
            String itemCode,
            String itemName,
            UUID storeId,
            String storeName,
            UUID siteId,
            String batchRef,
            String lotNumber,
            UUID grnLineId,
            BigDecimal qty,
            BigDecimal reservedQty,
            BigDecimal availableQty,
            BigDecimal unitCost,
            UUID uomId,
            String uomSymbol,
            String status,
            LocalDate expiryDate,
            Instant createdAt
    ) {}

    public record StockMovementResponse(
            UUID id,
            UUID lotId,
            String movementType,
            BigDecimal qty,
            String referenceType,
            UUID referenceId,
            UUID performedBy,
            String note,
            Instant createdAt
    ) {}

    public record InventoryPositionResponse(
            UUID itemId,
            String itemCode,
            String itemName,
            UUID storeId,
            String storeName,
            BigDecimal totalQty,
            BigDecimal reservedQty,
            BigDecimal availableQty,
            BigDecimal inTransitQty,
            BigDecimal valuationAmt,
            BigDecimal reorderPoint,
            String uomSymbol,
            Instant lastMovedAt
    ) {}

    public record ReorderPolicyRequest(
            @NotNull UUID itemId,
            @NotNull UUID storeId,
            @NotNull @DecimalMin(value = "0.000") BigDecimal reorderPoint,
            @NotNull @DecimalMin(value = "0.001") BigDecimal reorderQty,
            @DecimalMin(value = "0.001") BigDecimal maxStock,
            Integer leadTimeDays,
            UUID preferredSupplierId,
            Boolean active
    ) {}

    public record ReorderPolicyResponse(
            UUID id,
            UUID itemId,
            UUID storeId,
            BigDecimal reorderPoint,
            BigDecimal reorderQty,
            BigDecimal maxStock,
            Integer leadTimeDays,
            UUID preferredSupplierId,
            boolean active
    ) {}

    public record ReplenishmentRequestResponse(
            UUID id,
            UUID itemId,
            String itemCode,
            String itemName,
            UUID storeId,
            String storeName,
            UUID policyId,
            BigDecimal requestedQty,
            String status,
            UUID prId,
            Instant createdAt
    ) {}

    public record TransferLineRequest(
            @NotNull UUID lotId,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qty
    ) {}

    public record CreateTransferRequest(
            @NotNull UUID fromStoreId,
            @NotNull UUID toStoreId,
            LocalDate transferDate,
            String note,
            @NotEmpty List<@Valid TransferLineRequest> lines
    ) {}

    public record ReceiveTransferRequest(String receivedNote) {}

    public record TransferLineResponse(
            UUID id,
            UUID lotId,
            String itemCode,
            String itemName,
            BigDecimal qty,
            String uomSymbol
    ) {}

    public record TransferResponse(
            UUID id,
            String transferNumber,
            UUID fromStoreId,
            String fromStoreName,
            UUID toStoreId,
            String toStoreName,
            String status,
            LocalDate transferDate,
            List<TransferLineResponse> lines,
            Instant createdAt,
            Instant receivedAt
    ) {}

    public record InventoryPage<T>(List<T> content, int page, long totalElements) {}

    public record JobReservationResponse(
            UUID jobId,
            String jobNumber,
            UUID siteId,
            UUID itemId,
            String itemCode,
            String itemName,
            BigDecimal reservedQty
    ) {}

    public record JobLotReservationResponse(
            UUID jobId,
            UUID lotId,
            UUID itemId,
            BigDecimal reservedQty
    ) {}
}
