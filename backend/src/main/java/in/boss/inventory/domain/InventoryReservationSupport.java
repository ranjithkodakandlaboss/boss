package in.boss.inventory.domain;

import in.boss.inventory.api.InventoryApiDtos.ReservationLineResponse;
import in.boss.inventory.api.InventoryApiDtos.ReservationResponse;
import in.boss.inventory.persistence.InventoryReservationRepository.JobMaterialSnapshotLine;
import in.boss.masterdata.api.dto.BOMExplosionMaterialDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class InventoryReservationSupport {

    private static final BigDecimal MIN_QTY = new BigDecimal("0.001");
    private static final Set<String> TERMINAL_JOB_STATUSES = Set.of("completed", "closed", "cancelled");

    private InventoryReservationSupport() {}

    static void validateJobStatus(String status) {
        if (status != null && TERMINAL_JOB_STATUSES.contains(status.toLowerCase(Locale.ROOT))) {
            throw new InventoryValidationException(
                    "Stock cannot be reserved for a production job in status '" + status + "'");
        }
    }

    static JobMaterialSnapshotLine snapshotLine(BOMExplosionMaterialDto material) {
        if (material.getUomId() == null) {
            throw new InventoryValidationException("BOM material is missing its unit of measure");
        }
        BigDecimal required = material.getQty().setScale(3, RoundingMode.HALF_UP);
        if (required.signum() <= 0) {
            throw new InventoryValidationException(
                    "BOM material quantity is below the supported inventory precision");
        }
        return new JobMaterialSnapshotLine(material.getItemId(), required, material.getUomId());
    }

    static BigDecimal quantity(BigDecimal value) {
        if (value == null) {
            throw new InventoryValidationException("Quantity is required");
        }
        BigDecimal scaled = value.setScale(3, RoundingMode.HALF_UP);
        if (scaled.compareTo(MIN_QTY) < 0) {
            throw new InventoryValidationException("Quantity must be at least 0.001");
        }
        return scaled;
    }

    static ReservationResponse failedReservation(UUID jobId, List<ReservationPlan> plans) {
        List<ReservationLineResponse> lines = plans.stream()
                .map(plan -> new ReservationLineResponse(plan.itemId(), plan.storeId(),
                        plan.requested(), BigDecimal.ZERO.setScale(3), plan.requested(), List.of()))
                .toList();
        return new ReservationResponse(jobId, "FAILED", lines);
    }
}
