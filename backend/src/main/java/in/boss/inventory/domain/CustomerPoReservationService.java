package in.boss.inventory.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.inventory.api.InventoryApiDtos.CustomerPoReservationLineResponse;
import in.boss.inventory.api.InventoryApiDtos.CustomerPoReservationRequest;
import in.boss.inventory.api.InventoryApiDtos.CustomerPoReservationResponse;
import in.boss.inventory.api.InventoryApiDtos.ReservationAllocation;
import in.boss.inventory.events.InventoryMetrics;
import in.boss.inventory.persistence.CustomerPoReservationRow;
import in.boss.inventory.persistence.InventoryRepository;
import in.boss.inventory.persistence.InventoryReservationRepository;
import in.boss.inventory.persistence.InventoryReservationRepository.CustomerPoHeader;
import in.boss.inventory.persistence.LotRow;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class CustomerPoReservationService {

    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InventoryReplenishmentService replenishmentService;
    private final InventoryFeatureFlags featureFlags;
    private final CustomerPoReservationPlanner planner;
    private final InventoryMetrics metrics;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public CustomerPoReservationService(InventoryRepository inventoryRepository,
                                       InventoryReservationRepository reservationRepository,
                                       InventoryReplenishmentService replenishmentService,
                                       InventoryFeatureFlags featureFlags,
                                       CustomerPoReservationPlanner planner,
                                       InventoryMetrics metrics,
                                       AuditService auditService,
                                       ObjectMapper objectMapper) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.replenishmentService = replenishmentService;
        this.featureFlags = featureFlags;
        this.planner = planner;
        this.metrics = metrics;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public CustomerPoReservationResponse reserve(CustomerPoReservationRequest request,
                                                 boolean allowPartial) {
        BossContext context = requireContext();
        featureFlags.require(context.tenantId(), InventoryFeatureFlags.CUSTOMER_PO_RESERVATIONS);
        CustomerPoHeader cpo = lockCustomerPo(context, request.cpoId());
        if (!Set.of("open", "partial").contains(cpo.status())) {
            throw new InventoryValidationException("Only open or partial Customer POs can reserve stock");
        }
        List<CustomerPoLinePlan> plans = planner.plan(context, request.cpoId(), cpo, request.lines());
        if (!allowPartial && plans.stream().anyMatch(plan -> plan.shortfall().signum() > 0)) {
            metrics.reservation("failed");
            return failed(request.cpoId(), plans);
        }

        Map<UUID, BigDecimal> currentReservedByLot = new HashMap<>();
        List<CustomerPoReservationLineResponse> results = new ArrayList<>();
        Set<StoreItem> changedBalances = new HashSet<>();
        for (CustomerPoLinePlan plan : plans) {
            List<ReservationAllocation> allocations = new ArrayList<>();
            for (CustomerPoPlannedLot planned : plan.allocations()) {
                LotRow lot = planned.lot();
                BigDecimal currentReserved = currentReservedByLot.getOrDefault(
                        lot.id(), lot.reservedQty());
                BigDecimal nextReserved = currentReserved.add(planned.quantity());
                reservationRepository.insertCustomerPoReservation(context.tenantId(), lot.id(),
                        lot.itemId(), planned.quantity(), request.cpoId(), plan.cpoLineId());
                reservationRepository.updateLotBalance(context.tenantId(), lot.id(),
                        lot.quantity(), nextReserved,
                        nextReserved.compareTo(lot.quantity()) == 0 ? LotStatus.RESERVED : LotStatus.AVAILABLE);
                currentReservedByLot.put(lot.id(), nextReserved);
                allocations.add(new ReservationAllocation(lot.id(), planned.quantity()));
                changedBalances.add(new StoreItem(lot.itemId(), lot.storeId()));
                auditService.record("STOCK_RESERVATION", lot.id(), "CREATE", null,
                        json(Map.of("cpoId", request.cpoId(), "cpoLineId", plan.cpoLineId(),
                                "itemId", lot.itemId(), "reservedQty", planned.quantity())));
            }
            results.add(new CustomerPoReservationLineResponse(plan.cpoLineId(), plan.itemId(),
                    plan.storeId(), plan.requested(), plan.reserved(), plan.shortfall(),
                    List.copyOf(allocations)));
        }
        changedBalances.forEach(balance -> replenishmentService.checkAfterStockChange(
                context.tenantId(), balance.itemId(), balance.storeId()));
        boolean fullyReserved = results.stream().allMatch(line -> line.shortfall().signum() == 0);
        boolean anyReserved = results.stream().anyMatch(line -> line.reserved().signum() > 0);
        String status = fullyReserved ? "FULLY_RESERVED"
                : anyReserved ? "PARTIALLY_RESERVED" : "FAILED";
        metrics.reservation(status.toLowerCase());
        return new CustomerPoReservationResponse(request.cpoId(), status, results);
    }

    public void release(UUID cpoId) {
        BossContext context = requireContext();
        lockCustomerPo(context, cpoId);
        List<CustomerPoReservationRow> reservations = reservationRepository.activeCustomerPoReservations(
                context.tenantId(), cpoId, true);
        if (reservations.isEmpty()) {
            return;
        }
        List<UUID> lotIds = reservations.stream().map(CustomerPoReservationRow::lotId)
                .distinct().sorted().toList();
        Map<UUID, LotRow> lots = new LinkedHashMap<>();
        for (UUID lotId : lotIds) {
            LotRow lot = inventoryRepository.findLot(context.tenantId(), lotId, true)
                    .orElseThrow(() -> new LotNotFoundException(lotId));
            if (!context.canAccessSite(lot.siteId())) {
                throw new LotNotFoundException(lotId);
            }
            lots.put(lotId, lot);
        }
        Map<UUID, BigDecimal> releasedByLot = new LinkedHashMap<>();
        Set<StoreItem> changedBalances = new HashSet<>();
        for (CustomerPoReservationRow reservation : reservations) {
            releasedByLot.merge(reservation.lotId(), reservation.reservedQty(), BigDecimal::add);
            reservationRepository.releaseReservation(context.tenantId(), reservation.id());
        }
        for (Map.Entry<UUID, BigDecimal> entry : releasedByLot.entrySet()) {
            LotRow lot = lots.get(entry.getKey());
            BigDecimal remainingReserved = lot.reservedQty().subtract(entry.getValue());
            if (remainingReserved.signum() < 0) {
                throw new IllegalStateException("Active reservations exceed the lot reserved balance");
            }
            String nextStatus = remainingReserved.compareTo(lot.quantity()) == 0
                    ? LotStatus.RESERVED : LotStatus.AVAILABLE;
            reservationRepository.updateLotBalance(context.tenantId(), lot.id(), lot.quantity(),
                    remainingReserved, nextStatus);
            changedBalances.add(new StoreItem(lot.itemId(), lot.storeId()));
            auditService.record("STOCK_RESERVATION", lot.id(), "RELEASE",
                    json(Map.of("cpoId", cpoId, "reservedQty", entry.getValue())),
                    json(Map.of("cpoId", cpoId, "reservedQty", BigDecimal.ZERO)));
        }
        changedBalances.forEach(balance -> replenishmentService.checkAfterStockChange(
                context.tenantId(), balance.itemId(), balance.storeId()));
    }

    private CustomerPoReservationResponse failed(UUID cpoId, List<CustomerPoLinePlan> plans) {
        List<CustomerPoReservationLineResponse> lines = plans.stream()
                .map(plan -> new CustomerPoReservationLineResponse(
                        plan.cpoLineId(), plan.itemId(), plan.storeId(), plan.requested(),
                        BigDecimal.ZERO.setScale(3), plan.requested(), List.of()))
                .toList();
        return new CustomerPoReservationResponse(cpoId, "FAILED", lines);
    }

    private CustomerPoHeader lockCustomerPo(BossContext context, UUID cpoId) {
        CustomerPoHeader cpo = reservationRepository.lockCustomerPo(context.tenantId(), cpoId)
                .orElseThrow(() -> new InventoryValidationException(
                        "Customer PO not found for this tenant"));
        if (!context.canAccessSite(cpo.siteId())) {
            throw new InventoryValidationException("Customer PO not found for this tenant");
        }
        return cpo;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise inventory audit payload", e);
        }
    }

    private static BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new IllegalStateException("Tenant context is required");
        }
        return context;
    }

    private record StoreItem(UUID itemId, UUID storeId) {}
}
