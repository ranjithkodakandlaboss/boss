package in.boss.inventory.domain;

import in.boss.inventory.api.InventoryApiDtos.CustomerPoReservationRequirement;
import in.boss.inventory.persistence.InventoryRepository;
import in.boss.inventory.persistence.InventoryReservationRepository;
import in.boss.inventory.persistence.InventoryReservationRepository.CustomerPoHeader;
import in.boss.inventory.persistence.InventoryReservationRepository.CustomerPoLine;
import in.boss.inventory.persistence.LotRow;
import in.boss.shared.context.BossContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class CustomerPoReservationPlanner {

    private static final BigDecimal MIN_QTY = new BigDecimal("0.001");

    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository reservationRepository;

    public CustomerPoReservationPlanner(
            InventoryRepository inventoryRepository,
            InventoryReservationRepository reservationRepository) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
    }

    public List<CustomerPoLinePlan> plan(
            BossContext context, UUID cpoId, CustomerPoHeader cpo,
            List<CustomerPoReservationRequirement> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            throw new InventoryValidationException("At least one Customer PO line is required");
        }
        Set<UUID> seenLines = new HashSet<>();
        Map<UUID, BigDecimal> plannedByLot = new HashMap<>();
        Map<UUID, LotRow> lotsById = new HashMap<>();
        List<CustomerPoLinePlan> plans = new ArrayList<>();
        for (CustomerPoReservationRequirement requirement : requirements) {
            if (!seenLines.add(requirement.cpoLineId())) {
                throw new InventoryValidationException("A Customer PO line can appear only once per request");
            }
            plans.add(planLine(context, cpoId, cpo, requirement, plannedByLot, lotsById));
        }
        return plans;
    }

    private CustomerPoLinePlan planLine(
            BossContext context, UUID cpoId, CustomerPoHeader cpo,
            CustomerPoReservationRequirement requirement,
            Map<UUID, BigDecimal> plannedByLot, Map<UUID, LotRow> lotsById) {
        BigDecimal requested = quantity(requirement.qty());
        CustomerPoLine line = reservationRepository.lockCustomerPoLine(
                        context.tenantId(), requirement.cpoLineId())
                .orElseThrow(() -> new InventoryValidationException(
                        "Customer PO line not found for this tenant"));
        if (!cpoId.equals(line.cpoId())) {
            throw new InventoryValidationException("Customer PO line does not belong to this order");
        }
        BigDecimal alreadyReserved = reservationRepository.activeCustomerPoReservedQty(
                context.tenantId(), requirement.cpoLineId());
        BigDecimal remainingDemand = line.quantity().subtract(line.deliveredQty()).subtract(alreadyReserved);
        if (remainingDemand.signum() <= 0 || requested.compareTo(remainingDemand) > 0) {
            throw new InventoryValidationException(
                    "Reservation quantity exceeds the unfulfilled quantity on the Customer PO line");
        }

        UUID storeSiteId = inventoryRepository.storeSiteId(context.tenantId(), requirement.storeId())
                .orElseThrow(() -> new InventoryValidationException("Store not found for this tenant"));
        if (!cpo.siteId().equals(storeSiteId) || !context.canAccessSite(storeSiteId)) {
            throw new InventoryValidationException("Store must belong to the Customer PO site");
        }

        AllocationPlan allocation = allocate(context, line.itemId(), requirement.storeId(),
                storeSiteId, requested, plannedByLot, lotsById);
        return new CustomerPoLinePlan(requirement.cpoLineId(), line.itemId(), requirement.storeId(),
                requested, allocation.reserved(), allocation.shortfall(), allocation.lots());
    }

    private AllocationPlan allocate(
            BossContext context, UUID itemId, UUID storeId, UUID siteId, BigDecimal requested,
            Map<UUID, BigDecimal> plannedByLot, Map<UUID, LotRow> lotsById) {
        List<CustomerPoPlannedLot> allocations = new ArrayList<>();
        BigDecimal remaining = requested;
        for (UUID lotId : reservationRepository.lockReservableLotIds(
                context.tenantId(), itemId, storeId, siteId)) {
            LotRow lot = lotsById.computeIfAbsent(lotId, id -> inventoryRepository
                    .findLot(context.tenantId(), id, false)
                    .orElseThrow(() -> new LotNotFoundException(id)));
            BigDecimal available = lot.quantity().subtract(lot.reservedQty())
                    .subtract(plannedByLot.getOrDefault(lotId, BigDecimal.ZERO));
            BigDecimal allocated = available.min(remaining);
            if (allocated.signum() > 0) {
                allocations.add(new CustomerPoPlannedLot(lot, allocated));
                plannedByLot.merge(lotId, allocated, BigDecimal::add);
                remaining = remaining.subtract(allocated);
            }
            if (remaining.signum() == 0) {
                break;
            }
        }
        return new AllocationPlan(requested.subtract(remaining), remaining, allocations);
    }

    private static BigDecimal quantity(BigDecimal value) {
        if (value == null) {
            throw new InventoryValidationException("Quantity is required");
        }
        BigDecimal scaled = value.setScale(3, RoundingMode.HALF_UP);
        if (scaled.compareTo(MIN_QTY) < 0) {
            throw new InventoryValidationException("Quantity must be at least 0.001");
        }
        return scaled;
    }

    private record AllocationPlan(
            BigDecimal reserved, BigDecimal shortfall, List<CustomerPoPlannedLot> lots) {}
}

record CustomerPoPlannedLot(LotRow lot, BigDecimal quantity) {}

record CustomerPoLinePlan(
        UUID cpoLineId, UUID itemId, UUID storeId, BigDecimal requested,
        BigDecimal reserved, BigDecimal shortfall, List<CustomerPoPlannedLot> allocations) {}
