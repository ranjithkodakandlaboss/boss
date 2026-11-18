package in.boss.inventory.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PositionRow(
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
