package in.boss.inventory.domain;

import in.boss.inventory.api.InventoryApiDtos.JobLotReservationResponse;
import in.boss.inventory.api.InventoryApiDtos.LotReservationResponse;
import in.boss.inventory.persistence.InventoryReservationRepository;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class InventoryLotReservationQuery {

    private final InventoryReservationRepository reservationRepository;

    public InventoryLotReservationQuery(InventoryReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    public List<LotReservationResponse> listLotReservations(UUID lotId) {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new IllegalStateException("Tenant context is required");
        }
        return reservationRepository.activeLotReservations(context.tenantId(), lotId).stream()
                .map(row -> new LotReservationResponse(
                        row.id(), row.referenceType(), row.referenceId(),
                        row.reservedQty(), row.documentNumber()))
                .toList();
    }

    public List<JobLotReservationResponse> listJobLotReservations(UUID jobId) {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new IllegalStateException("Tenant context is required");
        }
        UUID siteId = context.isSiteScoped() ? context.siteId() : null;
        return reservationRepository.activeProductionLotReservations(context.tenantId(), jobId, siteId).stream()
                .map(row -> new JobLotReservationResponse(row.jobId(), row.lotId(), row.itemId(), row.reservedQty()))
                .toList();
    }
}
