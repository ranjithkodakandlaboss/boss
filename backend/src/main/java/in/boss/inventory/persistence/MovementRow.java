package in.boss.inventory.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MovementRow(
        UUID id,
        UUID lotId,
        String movementType,
        BigDecimal quantity,
        String referenceType,
        UUID referenceId,
        UUID performedBy,
        String notes,
        Instant createdAt
) {}
