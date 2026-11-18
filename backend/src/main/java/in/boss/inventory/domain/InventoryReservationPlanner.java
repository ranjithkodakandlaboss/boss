package in.boss.inventory.domain;

import in.boss.inventory.api.InventoryApiDtos.ReservationRequirement;
import in.boss.inventory.persistence.InventoryRepository;
import in.boss.inventory.persistence.InventoryReservationRepository;
import in.boss.inventory.persistence.LotRow;
import in.boss.shared.context.BossContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class InventoryReservationPlanner {

    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository reservationRepository;

    public InventoryReservationPlanner(
            InventoryRepository inventoryRepository,
            InventoryReservationRepository reservationRepository) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
    }

    public List<ReservationPlan> planRequirements(
            BossContext context, List<ReservationRequirement> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            throw new InventoryValidationException("At least one material requirement is required");
        }
        Map<ItemStore, BigDecimal> requestedByItemStore = new LinkedHashMap<>();
        Map<ItemStore, UUID> siteByItemStore = new LinkedHashMap<>();
        for (ReservationRequirement requirement : requirements) {
            ItemStore key = new ItemStore(requirement.itemId(), requirement.storeId());
            BigDecimal requested = reservationQuantity(requirement.qty());
            if (!inventoryRepository.itemExists(context.tenantId(), requirement.itemId())) {
                throw new InventoryValidationException(
                        "Item not found for this tenant: " + requirement.itemId());
            }
            UUID siteId = requireStore(context, requirement.storeId());
            requestedByItemStore.merge(key, requested, BigDecimal::add);
            siteByItemStore.putIfAbsent(key, siteId);
        }
        List<ReservationPlan> plans = new ArrayList<>();
        for (Map.Entry<ItemStore, BigDecimal> entry : requestedByItemStore.entrySet()) {
            plans.add(planRequirement(context, entry.getKey(), entry.getValue(),
                    siteByItemStore.get(entry.getKey())));
        }
        return plans;
    }

    public List<ReservationPlan> planSiteRequirements(
            BossContext context, UUID siteId, Map<UUID, BigDecimal> requestedByItem) {
        List<ReservationPlan> plans = new ArrayList<>();
        requestedByItem.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    BigDecimal remaining = entry.getValue();
                    List<PlannedLot> allocations = new ArrayList<>();
                    for (UUID lotId : reservationRepository.lockReservableLotIdsAtSite(
                            context.tenantId(), entry.getKey(), siteId)) {
                        LotRow lot = inventoryRepository.findLot(context.tenantId(), lotId, false)
                                .orElseThrow(() -> new LotNotFoundException(lotId));
                        BigDecimal available = lot.quantity().subtract(lot.reservedQty());
                        BigDecimal allocated = available.min(remaining);
                        if (allocated.signum() > 0) {
                            allocations.add(new PlannedLot(lot, allocated));
                            remaining = remaining.subtract(allocated);
                        }
                        if (remaining.signum() == 0) {
                            break;
                        }
                    }
                    BigDecimal requested = entry.getValue();
                    plans.add(new ReservationPlan(entry.getKey(), null, requested,
                            requested.subtract(remaining), remaining, allocations));
                });
        return plans;
    }

    private ReservationPlan planRequirement(
            BossContext context, ItemStore key, BigDecimal requested, UUID siteId) {
        BigDecimal remaining = requested;
        List<PlannedLot> allocations = new ArrayList<>();
        for (UUID lotId : reservationRepository.lockReservableLotIds(
                context.tenantId(), key.itemId(), key.storeId(), siteId)) {
            LotRow lot = inventoryRepository.findLot(context.tenantId(), lotId, false)
                    .orElseThrow(() -> new LotNotFoundException(lotId));
            BigDecimal available = lot.quantity().subtract(lot.reservedQty());
            BigDecimal allocated = available.min(remaining);
            if (allocated.signum() > 0) {
                allocations.add(new PlannedLot(lot, allocated));
                remaining = remaining.subtract(allocated);
            }
            if (remaining.signum() == 0) {
                break;
            }
        }
        return new ReservationPlan(key.itemId(), key.storeId(), requested,
                requested.subtract(remaining), remaining, allocations);
    }

    private UUID requireStore(BossContext context, UUID storeId) {
        UUID siteId = inventoryRepository.storeSiteId(context.tenantId(), storeId)
                .orElseThrow(() -> new InventoryValidationException(
                        "Store not found for this tenant: " + storeId));
        if (!context.canAccessSite(siteId)) {
            throw new InventoryValidationException("Store is outside the caller's site");
        }
        return siteId;
    }

    private static BigDecimal reservationQuantity(BigDecimal value) {
        if (value == null) {
            throw new InventoryValidationException("Quantity is required");
        }
        BigDecimal scaled = value.setScale(3, java.math.RoundingMode.HALF_UP);
        if (scaled.compareTo(new BigDecimal("0.001")) < 0) {
            throw new InventoryValidationException("Quantity must be at least 0.001");
        }
        return scaled;
    }
}

record ItemStore(UUID itemId, UUID storeId) {}

record PlannedLot(LotRow lot, BigDecimal quantity) {}

record ReservationPlan(UUID itemId, UUID storeId, BigDecimal requested,
                       BigDecimal reserved, BigDecimal shortfall, List<PlannedLot> allocations) {}
