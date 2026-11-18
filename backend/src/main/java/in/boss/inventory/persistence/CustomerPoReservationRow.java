package in.boss.inventory.persistence;

import java.math.BigDecimal;
import java.util.UUID;

public record CustomerPoReservationRow(
        UUID id,
        UUID lotId,
        UUID itemId,
        UUID cpoLineId,
        BigDecimal reservedQty
) {}
