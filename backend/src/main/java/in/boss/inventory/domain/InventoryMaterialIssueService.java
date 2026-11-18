package in.boss.inventory.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.inventory.api.InventoryApiDtos.IssueMaterialRequest;
import in.boss.inventory.api.InventoryApiDtos.ReturnMaterialRequest;
import in.boss.inventory.api.InventoryApiDtos.StockMovementResponse;
import in.boss.inventory.events.InventoryEventPublisher;
import in.boss.inventory.events.InventoryMetrics;
import in.boss.inventory.persistence.InventoryRepository;
import in.boss.inventory.persistence.InventoryReservationRepository;
import in.boss.inventory.persistence.LotRow;
import in.boss.inventory.persistence.MovementRow;
import in.boss.inventory.persistence.ReservationRow;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class InventoryMaterialIssueService {

    private static final BigDecimal MIN_QTY = new BigDecimal("0.001");

    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InventoryReplenishmentService replenishmentService;
    private final InventoryFeatureFlags featureFlags;
    private final InventoryMetrics metrics;
    private final AuditService auditService;
    private final InventoryEventPublisher events;
    private final ObjectMapper objectMapper;

    public InventoryMaterialIssueService(InventoryRepository inventoryRepository,
                                         InventoryReservationRepository reservationRepository,
                                         InventoryReplenishmentService replenishmentService,
                                         InventoryFeatureFlags featureFlags,
                                         InventoryMetrics metrics,
                                         AuditService auditService,
                                         InventoryEventPublisher events,
                                         ObjectMapper objectMapper) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.replenishmentService = replenishmentService;
        this.featureFlags = featureFlags;
        this.metrics = metrics;
        this.auditService = auditService;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    public StockMovementResponse issueMaterial(IssueMaterialRequest request) {
        return issueReservedMaterial(request, true);
    }

    public StockMovementResponse issueReservedMaterial(IssueMaterialRequest request) {
        return issueReservedMaterial(request, false);
    }

    private StockMovementResponse issueReservedMaterial(
            IssueMaterialRequest request, boolean recordJobMaterialIssue) {
        BossContext ctx = requireContext();
        featureFlags.require(ctx.tenantId(), InventoryFeatureFlags.MATERIAL_ISSUE);
        requireLockedJob(ctx, request.jobId());
        LotRow lot = requireVisibleLot(ctx, request.lotId());
        BigDecimal qty = quantity(request.qty());
        if (!LotStatus.AVAILABLE.equals(lot.status()) && !LotStatus.RESERVED.equals(lot.status())) {
            throw new InventoryValidationException("Only usable, non-quarantined lots can be issued");
        }
        List<ReservationRow> reservations = reservationRepository.activeReservations(
                ctx.tenantId(), request.jobId(), lot.id(), true);
        BigDecimal reservedForJob = reservations.stream()
                .map(ReservationRow::reservedQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (qty.compareTo(reservedForJob) > 0) {
            throw new InventoryValidationException(
                    "Issue quantity exceeds the active reservation for this job and lot");
        }
        consumeReservations(ctx.tenantId(), reservations, qty);

        BigDecimal newQty = lot.quantity().subtract(qty);
        BigDecimal newReservedQty = lot.reservedQty().subtract(qty);
        String nextStatus = LotStatusMachine.statusAfterMovement(
                lot.status(), MovementType.ISSUE, newQty, newReservedQty);
        UUID movementId = appendMovement(ctx, lot, MovementType.ISSUE, qty,
                request.jobId(), blankToNull(request.note()));
        reservationRepository.updateLotBalance(ctx.tenantId(), lot.id(), newQty,
                newReservedQty, nextStatus);
        if (recordJobMaterialIssue) {
            reservationRepository.insertJobMaterialIssue(ctx.tenantId(), request.jobId(), lot.itemId(),
                    lot.id(), qty, lot.uomId(), "issue", lot.storeId(), ctx.userId(),
                    blankToNull(request.note()));
        }
        publishMovement(movementId, lot, MovementType.ISSUE, qty);
        replenishmentService.checkAfterStockChange(ctx.tenantId(), lot.itemId(), lot.storeId());
        metrics.materialIssue();
        return findMovement(ctx.tenantId(), movementId);
    }

    public StockMovementResponse returnMaterial(ReturnMaterialRequest request) {
        BossContext ctx = requireContext();
        featureFlags.require(ctx.tenantId(), InventoryFeatureFlags.MATERIAL_RETURN);
        requireLockedJob(ctx, request.jobId());
        LotRow lot = requireVisibleLot(ctx, request.lotId());
        BigDecimal qty = quantity(request.qty());
        BigDecimal remainingIssued = reservationRepository.jobIssuedQty(
                ctx.tenantId(), request.jobId(), lot.id());
        if (qty.compareTo(remainingIssued) > 0) {
            throw new InventoryValidationException(
                    "Return quantity exceeds the unreturned issue quantity for this job and lot");
        }

        BigDecimal newQty = lot.quantity().add(qty);
        String nextStatus = LotStatusMachine.statusAfterMovement(
                lot.status(), MovementType.RETURN, newQty, lot.reservedQty());
        String note = blankToNull(request.returnReason());
        UUID movementId = appendMovement(ctx, lot, MovementType.RETURN, qty, request.jobId(), note);
        reservationRepository.updateLotBalance(ctx.tenantId(), lot.id(), newQty,
                lot.reservedQty(), nextStatus);
        reservationRepository.insertJobMaterialIssue(ctx.tenantId(), request.jobId(), lot.itemId(),
                lot.id(), qty, lot.uomId(), "return", lot.storeId(), ctx.userId(), note);
        publishMovement(movementId, lot, MovementType.RETURN, qty);
        replenishmentService.checkAfterStockChange(ctx.tenantId(), lot.itemId(), lot.storeId());
        metrics.materialReturn();
        return findMovement(ctx.tenantId(), movementId);
    }

    private void consumeReservations(UUID tenantId, List<ReservationRow> reservations, BigDecimal qty) {
        BigDecimal remaining = qty;
        for (ReservationRow reservation : reservations) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal consumed = reservation.reservedQty().min(remaining);
            reservationRepository.reduceReservation(tenantId, reservation.id(), consumed,
                    consumed.compareTo(reservation.reservedQty()) == 0);
            remaining = remaining.subtract(consumed);
        }
    }

    private UUID appendMovement(BossContext ctx, LotRow lot, MovementType type,
                                BigDecimal quantity, UUID jobId, String note) {
        UUID movementId = UUID.randomUUID();
        inventoryRepository.insertMovement(movementId, ctx.tenantId(), type.dbValue(),
                lot.itemId(), lot.id(), type.inbound() ? null : lot.storeId(),
                type.inbound() ? lot.storeId() : null, quantity, lot.unitCost(),
                "JOB", jobId, ctx.userId(), note);
        return movementId;
    }

    private void publishMovement(UUID movementId, LotRow lot, MovementType type, BigDecimal qty) {
        Map<String, Object> snapshot = movementSnapshot(movementId, lot, type, qty);
        auditService.record("STOCK_MOVEMENT", movementId, "CREATE", null, json(snapshot));
        events.movementCreated(movementId, snapshot);
    }

    private StockMovementResponse findMovement(UUID tenantId, UUID movementId) {
        MovementRow movement = inventoryRepository.findMovement(tenantId, movementId)
                .orElseThrow(() -> new IllegalStateException("Inserted movement was not readable"));
        MovementType type = MovementType.parse(movement.movementType());
        BigDecimal signed = type.inbound() ? movement.quantity() : movement.quantity().negate();
        return new StockMovementResponse(movement.id(), movement.lotId(), movement.movementType(),
                signed, movement.referenceType(), movement.referenceId(), movement.performedBy(),
                movement.notes(), movement.createdAt());
    }

    private LotRow requireVisibleLot(BossContext ctx, UUID lotId) {
        LotRow lot = inventoryRepository.findLot(ctx.tenantId(), lotId, true)
                .orElseThrow(() -> new LotNotFoundException(lotId));
        if (!ctx.canAccessSite(lot.siteId())) {
            throw new LotNotFoundException(lotId);
        }
        return lot;
    }

    private void requireLockedJob(BossContext ctx, UUID jobId) {
        UUID siteId = reservationRepository.lockProductionJob(ctx.tenantId(), jobId)
                .orElseThrow(() -> new InventoryValidationException(
                        "Production job not found for this tenant"));
        if (!ctx.canAccessSite(siteId)) {
            throw new InventoryValidationException("Production job not found for this tenant");
        }
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, Object> movementSnapshot(
            UUID movementId, LotRow lot, MovementType type, BigDecimal qty) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", movementId);
        snapshot.put("lotId", lot.id());
        snapshot.put("itemId", lot.itemId());
        snapshot.put("movementType", type.dbValue());
        snapshot.put("qty", qty);
        return snapshot;
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
