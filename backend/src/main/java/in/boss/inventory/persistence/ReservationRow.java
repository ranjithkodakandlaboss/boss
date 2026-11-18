package in.boss.inventory.persistence;

import java.math.BigDecimal;
import java.util.UUID;

public record ReservationRow(
        UUID id,
        UUID lotId,
        UUID itemId,
        BigDecimal reservedQty
) {}
