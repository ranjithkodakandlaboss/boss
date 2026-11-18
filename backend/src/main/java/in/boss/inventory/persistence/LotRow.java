package in.boss.inventory.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LotRow(
        UUID id,
        UUID itemId,
        String itemCode,
        String itemName,
        UUID storeId,
        String storeName,
        UUID siteId,
        String batchNumber,
        String lotNumber,
        UUID grnLineId,
        BigDecimal quantity,
        BigDecimal reservedQty,
        BigDecimal unitCost,
        UUID uomId,
        String uomSymbol,
        String status,
        LocalDate expiryDate,
        Instant createdAt
) {}
