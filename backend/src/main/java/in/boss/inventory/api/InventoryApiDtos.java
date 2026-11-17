package in.boss.inventory.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
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

    public record MaterialLotResponse(
            UUID id,
            UUID itemId,
            String itemCode,
            String itemName,
            UUID storeId,
            String storeName,
            UUID siteId,
            String batchRef,
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
            BigDecimal valuationAmt,
            String uomSymbol,
            Instant lastMovedAt
    ) {}

    public record InventoryPage<T>(List<T> content, int page, long totalElements) {}
}
