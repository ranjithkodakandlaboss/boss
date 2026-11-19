package in.boss.inventory.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.inventory.api.InventoryApiDtos.FeasibilityResponse;
import in.boss.inventory.api.InventoryApiDtos.ReservationAllocation;
import in.boss.inventory.api.InventoryApiDtos.ReservationLineResponse;
import in.boss.inventory.events.InventoryMetrics;
import in.boss.inventory.persistence.InventoryReservationRepository;
import in.boss.inventory.persistence.InventoryReservationRepository.CustomerPoHeader;
import in.boss.inventory.persistence.InventoryReservationRepository.CustomerPoLine;
import in.boss.inventory.persistence.LotRow;
import in.boss.masterdata.domain.BomExplosionService;
import in.boss.masterdata.persistence.ItemRepository;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Explodes the approved BOM of every Customer PO line and reserves available raw materials at the
 * CPO site in one transaction. Materials with sufficient stock are reserved; materials that are
 * short are reported as shortfalls so the caller can raise purchase requisitions for them.
 */
@Service
@Transactional
public class FeasibilityCheckService {

    private static final BigDecimal MIN_QTY = new BigDecimal("0.001");

    private final InventoryReservationRepository reservationRepository;
    private final InventoryReservationPlanner planner;
    private final BomExplosionService bomExplosionService;
    private final CustomerPoReservationService customerPoReservationService;
    private final InventoryReplenishmentService replenishmentService;
    private final InventoryFeatureFlags featureFlags;
    private final InventoryMetrics metrics;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ItemRepository itemRepository;

    public FeasibilityCheckService(InventoryReservationRepository reservationRepository,
                                   InventoryReservationPlanner planner,
                                   BomExplosionService bomExplosionService,
                                   CustomerPoReservationService customerPoReservationService,
                                   InventoryReplenishmentService replenishmentService,
                                   InventoryFeatureFlags featureFlags,
                                   InventoryMetrics metrics,
                                   AuditService auditService,
                                   ObjectMapper objectMapper,
                                   ItemRepository itemRepository) {
        this.reservationRepository = reservationRepository;
        this.planner = planner;
        this.bomExplosionService = bomExplosionService;
        this.customerPoReservationService = customerPoReservationService;
        this.replenishmentService = replenishmentService;
        this.featureFlags = featureFlags;
        this.metrics = metrics;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.itemRepository = itemRepository;
    }

    public FeasibilityResponse checkAndReserve(UUID cpoId) {
        BossContext context = requireContext();
        featureFlags.require(context.tenantId(), InventoryFeatureFlags.CUSTOMER_PO_RESERVATIONS);
        CustomerPoHeader cpo = reservationRepository.lockCustomerPo(context.tenantId(), cpoId)
                .filter(header -> context.canAccessSite(header.siteId()))
                .orElseThrow(() -> new InventoryValidationException(
                        "Customer PO not found for this tenant"));
        if (!Set.of("open", "partial", "feasibility_pending").contains(cpo.status())) {
            throw new InventoryValidationException(
                    "Only open, partial, or feasibility-pending Customer POs can reserve stock");
        }
        if (!reservationRepository.activeCustomerPoReservations(context.tenantId(), cpoId, false).isEmpty()) {
            throw new ReservationConflictException(
                    "This Customer PO already has active stock reservations");
        }

        Map<UUID, BigDecimal> required = explodeRequirements(context, cpoId);
        List<ReservationPlan> plans = planner.planSiteRequirements(context, cpo.siteId(), required);
        boolean hasShortfall = plans.stream().anyMatch(plan -> plan.shortfall().signum() > 0);

        // Reserve available quantities for all materials that have stock, even when some are short.
        // Materials with no available stock produce a zero-allocation line with a shortfall.
        List<ReservationLineResponse> lines = new ArrayList<>();
        Set<StoreItem> changed = new HashSet<>();
        for (ReservationPlan plan : plans) {
            List<ReservationAllocation> allocations = new ArrayList<>();
            for (PlannedLot planned : plan.allocations()) {
                LotRow lot = planned.lot();
                BigDecimal nextReserved = lot.reservedQty().add(planned.quantity());
                reservationRepository.insertCustomerPoReservation(context.tenantId(), lot.id(),
                        lot.itemId(), planned.quantity(), cpoId, null);
                reservationRepository.updateLotBalance(context.tenantId(), lot.id(), lot.quantity(),
                        nextReserved, nextReserved.compareTo(lot.quantity()) == 0
                                ? LotStatus.RESERVED : LotStatus.AVAILABLE);
                allocations.add(new ReservationAllocation(lot.id(), planned.quantity()));
                changed.add(new StoreItem(lot.itemId(), lot.storeId()));
                auditService.record("STOCK_RESERVATION", lot.id(), "CREATE", null,
                        json(Map.of("cpoId", cpoId, "itemId", lot.itemId(),
                                "reservedQty", planned.quantity())));
            }
            lines.add(new ReservationLineResponse(plan.itemId(), null, plan.requested(),
                    plan.reserved(), plan.shortfall(), List.copyOf(allocations)));
        }
        changed.forEach(key -> replenishmentService.checkAfterStockChange(
                context.tenantId(), key.itemId(), key.storeId()));
        if (hasShortfall) {
            metrics.reservation("partially_reserved");
        } else {
            metrics.reservation("fully_reserved");
        }
        return new FeasibilityResponse(cpoId, !hasShortfall, lines);
    }

    public void releaseReservations(UUID cpoId) {
        customerPoReservationService.release(cpoId);
    }

    private Map<UUID, BigDecimal> explodeRequirements(BossContext context, UUID cpoId) {
        Map<UUID, BigDecimal> totals = new LinkedHashMap<>();
        for (CustomerPoLine line : reservationRepository.customerPoLines(context.tenantId(), cpoId)) {
            BigDecimal remaining = line.quantity().subtract(line.deliveredQty());
            if (remaining.signum() <= 0) {
                continue;
            }
            var result = bomExplosionService.explodeBOM(line.itemId(), remaining);
            var materials = result.getFlatMaterials();
            if (materials == null || materials.isEmpty()) {
                // No approved BOM — treat as a traded/stocked finished good:
                // reserve the finished-good SKU itself from inventory.
                var item = itemRepository.findByIdAndTenantId(line.itemId(), context.tenantId())
                        .orElseThrow(() -> new InventoryValidationException(
                                "Item on Customer PO line not found"));
                if (!"finished_goods".equals(item.getItemType())) {
                    throw new InventoryValidationException(
                            "A Customer PO line has no approved BOM with material requirements");
                }
                // Traded or not-yet-manufactured: reserve the finished-good item itself.
                totals.merge(line.itemId(), remaining, BigDecimal::add);
                continue;
            }
            materials.forEach(material -> totals.merge(material.getItemId(), material.getQty(),
                    BigDecimal::add));
        }
        if (totals.isEmpty()) {
            throw new InventoryValidationException("The Customer PO has no material requirements");
        }
        Map<UUID, BigDecimal> rounded = new LinkedHashMap<>();
        totals.forEach((itemId, qty) -> {
            BigDecimal scaled = qty.setScale(3, RoundingMode.HALF_UP);
            if (scaled.compareTo(MIN_QTY) < 0) {
                throw new InventoryValidationException(
                        "BOM material quantity is below the supported inventory precision");
            }
            rounded.put(itemId, scaled);
        });
        return rounded;
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
