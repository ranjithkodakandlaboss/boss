package in.boss.inventory.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.inventory.persistence.InventoryRepository;
import in.boss.inventory.persistence.InventoryReservationRepository;
import in.boss.inventory.persistence.LotRow;
import in.boss.inventory.persistence.ReservationRow;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class InventoryReservationReleaseService {

    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InventoryReplenishmentService replenishmentService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public InventoryReservationReleaseService(InventoryRepository inventoryRepository,
                                              InventoryReservationRepository reservationRepository,
                                              InventoryReplenishmentService replenishmentService,
                                              AuditService auditService,
                                              ObjectMapper objectMapper) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.replenishmentService = replenishmentService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public void releaseJobReservations(UUID jobId) {
        BossContext ctx = requireContext();
        requireLockedJob(ctx, jobId);
        List<ReservationRow> reservations = reservationRepository.activeReservations(
                ctx.tenantId(), jobId, null, false);
        if (reservations.isEmpty()) {
            return;
        }

        Map<UUID, LotRow> lots = lockLots(ctx, reservations);
        reservations = reservationRepository.activeReservations(ctx.tenantId(), jobId, null, true);
        Map<UUID, BigDecimal> releasedByLot = new LinkedHashMap<>();
        for (ReservationRow reservation : reservations) {
            releasedByLot.merge(reservation.lotId(), reservation.reservedQty(), BigDecimal::add);
            reservationRepository.releaseReservation(ctx.tenantId(), reservation.id());
        }
        for (Map.Entry<UUID, BigDecimal> entry : releasedByLot.entrySet()) {
            LotRow lot = lots.get(entry.getKey());
            BigDecimal remainingReserved = lot.reservedQty().subtract(entry.getValue());
            if (remainingReserved.signum() < 0) {
                throw new IllegalStateException("Active reservations exceed the lot reserved balance");
            }
            String nextStatus = remainingReserved.compareTo(lot.quantity()) == 0
                    ? LotStatus.RESERVED : LotStatus.AVAILABLE;
            reservationRepository.updateLotBalance(ctx.tenantId(), lot.id(), lot.quantity(),
                    remainingReserved, nextStatus);
            auditService.record("STOCK_RESERVATION", lot.id(), "RELEASE",
                    json(Map.of("jobId", jobId, "reservedQty", entry.getValue())),
                    json(Map.of("jobId", jobId, "reservedQty", BigDecimal.ZERO)));
            replenishmentService.checkAfterStockChange(ctx.tenantId(), lot.itemId(), lot.storeId());
        }
    }

    private Map<UUID, LotRow> lockLots(BossContext ctx, List<ReservationRow> reservations) {
        List<UUID> lotIds = reservations.stream().map(ReservationRow::lotId)
                .distinct().sorted().toList();
        Map<UUID, LotRow> lots = new LinkedHashMap<>();
        for (UUID lotId : lotIds) {
            LotRow lot = inventoryRepository.findLot(ctx.tenantId(), lotId, true)
                    .orElseThrow(() -> new LotNotFoundException(lotId));
            if (!ctx.canAccessSite(lot.siteId())) {
                throw new LotNotFoundException(lotId);
            }
            lots.put(lotId, lot);
        }
        return lots;
    }

    private void requireLockedJob(BossContext ctx, UUID jobId) {
        UUID siteId = reservationRepository.lockProductionJob(ctx.tenantId(), jobId)
                .orElseThrow(() -> new InventoryValidationException(
                        "Production job not found for this tenant"));
        if (!ctx.canAccessSite(siteId)) {
            throw new InventoryValidationException("Production job not found for this tenant");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise inventory audit payload", e);
        }
    }

    private static BossContext requireContext() {
        BossContext ctx = BossContextHolder.get();
        if (ctx == null || ctx.tenantId() == null) {
            throw new IllegalStateException("Tenant context is required");
        }
        return ctx;
    }
}
