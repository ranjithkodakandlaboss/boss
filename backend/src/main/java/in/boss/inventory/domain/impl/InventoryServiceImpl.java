package in.boss.inventory.domain.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.inventory.api.InventoryApiDtos.AddMovementRequest;
import in.boss.inventory.api.InventoryApiDtos.CreateLotRequest;
import in.boss.inventory.api.InventoryApiDtos.CustomerPoReservationRequest;
import in.boss.inventory.api.InventoryApiDtos.CustomerPoReservationResponse;
import in.boss.inventory.api.InventoryApiDtos.InventoryPage;
import in.boss.inventory.api.InventoryApiDtos.JobReservationResponse;
import in.boss.inventory.api.InventoryApiDtos.InventoryPositionResponse;
import in.boss.inventory.api.InventoryApiDtos.IssueMaterialRequest;
import in.boss.inventory.api.InventoryApiDtos.LotReservationResponse;
import in.boss.inventory.api.InventoryApiDtos.MaterialLotResponse;
import in.boss.inventory.api.InventoryApiDtos.ReservationRequest;
import in.boss.inventory.api.InventoryApiDtos.ReservationResponse;
import in.boss.inventory.api.InventoryApiDtos.ReturnMaterialRequest;
import in.boss.inventory.api.InventoryApiDtos.StockMovementResponse;
import in.boss.inventory.domain.InventoryService;
import in.boss.inventory.domain.InventoryMaterialIssueService;
import in.boss.inventory.domain.InventoryReplenishmentService;
import in.boss.inventory.domain.CustomerPoReservationService;
import in.boss.inventory.domain.InventoryReservationService;
import in.boss.inventory.domain.InventoryReservationReleaseService;
import in.boss.inventory.domain.InventoryValidationException;
import in.boss.inventory.domain.LotNotFoundException;
import in.boss.inventory.domain.LotStatus;
import in.boss.inventory.domain.LotStatusMachine;
import in.boss.inventory.domain.MovementType;
import in.boss.inventory.events.InventoryEventPublisher;
import in.boss.inventory.persistence.GrnLineRef;
import in.boss.inventory.persistence.InventoryRepository;
import in.boss.inventory.persistence.InventoryReservationRepository;
import in.boss.inventory.persistence.LotRow;
import in.boss.inventory.persistence.MovementRow;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class InventoryServiceImpl implements InventoryService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final BigDecimal MIN_QTY = new BigDecimal("0.001");

    private final InventoryRepository repository;
    private final InventoryReservationRepository reservationRepository;
    private final AuditService auditService;
    private final InventoryEventPublisher events;
    private final ObjectMapper objectMapper;
    private final InventoryReservationService reservationService;
    private final InventoryMaterialIssueService materialIssueService;
    private final InventoryReservationReleaseService reservationReleaseService;
    private final InventoryReplenishmentService replenishmentService;
    private final CustomerPoReservationService customerPoReservationService;
    private final in.boss.inventory.domain.FeasibilityCheckService feasibilityCheckService;

    public InventoryServiceImpl(InventoryRepository repository,
                                InventoryReservationRepository reservationRepository,
                                AuditService auditService,
                                InventoryEventPublisher events,
                                ObjectMapper objectMapper,
                                InventoryReservationService reservationService,
                                InventoryMaterialIssueService materialIssueService,
                                InventoryReservationReleaseService reservationReleaseService,
                                InventoryReplenishmentService replenishmentService,
                                CustomerPoReservationService customerPoReservationService,
                                in.boss.inventory.domain.FeasibilityCheckService feasibilityCheckService) {
        this.repository = repository;
        this.reservationRepository = reservationRepository;
        this.auditService = auditService;
        this.events = events;
        this.objectMapper = objectMapper;
        this.reservationService = reservationService;
        this.materialIssueService = materialIssueService;
        this.reservationReleaseService = reservationReleaseService;
        this.replenishmentService = replenishmentService;
        this.customerPoReservationService = customerPoReservationService;
        this.feasibilityCheckService = feasibilityCheckService;
    }

    @Override
    public MaterialLotResponse createLot(CreateLotRequest request, String preferredLotNumber) {
        BossContext ctx = requireContext();
        String status = LotStatus.canonical(request.status());
        LotStatus.assertCreatable(status);
        BigDecimal qty = quantity(request.qty());
        BigDecimal unitCost = unitCost(request.unitCost());
        requireItem(ctx.tenantId(), request.itemId());
        requireStore(ctx, request.storeId());
        requireGrnLine(ctx.tenantId(), request.grnLineId(), request.itemId(), request.storeId());

        UUID lotId = UUID.randomUUID();
        String preferred = blankToNull(preferredLotNumber);
        if (preferred != null && repository.lotNumberExists(ctx.tenantId(), preferred)) {
            throw new InventoryValidationException("Lot number " + preferred + " already exists");
        }
        String lotNumber = preferred != null ? preferred : "LOT-" + lotId.toString().substring(0, 8).toUpperCase();
        repository.insertLot(lotId, ctx.tenantId(), request.itemId(), request.storeId(), lotNumber,
                blankToNull(request.batchRef()), qty, unitCost, status, request.expiryDate());

        MovementType opening = request.grnLineId() == null ? MovementType.OPENING_BALANCE : MovementType.RECEIPT;
        String referenceType = request.grnLineId() == null ? null : "GRN";
        UUID movementId = appendMovement(ctx, lotId, request.itemId(), request.storeId(), opening, qty,
                unitCost, referenceType, request.grnLineId(), null);

        LotRow lot = requireVisibleLot(ctx, lotId, false);
        Map<String, Object> snapshot = lotSnapshot(lot);
        auditService.record("LOT", lotId, "CREATE", null, json(snapshot));
        events.lotCreated(lotId, snapshot);
        events.movementCreated(movementId, movementSnapshot(movementId, lot, opening, qty));
        replenishmentService.checkAfterStockChange(ctx.tenantId(), request.itemId(), request.storeId());
        return toLot(lot);
    }

    @Override
    public StockMovementResponse addMovement(UUID lotId, AddMovementRequest request) {
        BossContext ctx = requireContext();
        LotRow lot = requireVisibleLot(ctx, lotId, true);
        MovementType type = MovementType.parse(request.movementType());
        BigDecimal qty = quantity(request.qty());
        BigDecimal newQty = type.inbound() ? lot.quantity().add(qty) : lot.quantity().subtract(qty);
        if (newQty.signum() < 0 || newQty.compareTo(lot.reservedQty()) < 0) {
            throw new InventoryValidationException(
                    "Movement quantity exceeds the unreserved quantity on the lot");
        }
        String nextStatus = LotStatusMachine.statusAfterMovement(
                lot.status(), type, newQty, lot.reservedQty());
        UUID movementId = appendMovement(ctx, lot.id(), lot.itemId(), lot.storeId(), type, qty,
                lot.unitCost(), blankToNull(request.referenceType()), request.referenceId(),
                blankToNull(request.note()));
        repository.updateLot(ctx.tenantId(), lot.id(), newQty, nextStatus);

        Map<String, Object> snapshot = movementSnapshot(movementId, lot, type, qty);
        auditService.record("STOCK_MOVEMENT", movementId, "CREATE", null, json(snapshot));
        if (!lot.status().equals(nextStatus)) {
            auditService.record("LOT", lot.id(), "STATUS",
                    json(Map.of("status", LotStatus.toApi(lot.status()))),
                    json(Map.of("status", LotStatus.toApi(nextStatus))));
        }
        events.movementCreated(movementId, snapshot);
        replenishmentService.checkAfterStockChange(ctx.tenantId(), lot.itemId(), lot.storeId());
        return toMovement(repository.findMovement(ctx.tenantId(), movementId)
                .orElseThrow(() -> new IllegalStateException("Inserted movement was not readable")));
    }

    @Override
    public ReservationResponse reserveForJob(ReservationRequest request, boolean allowPartial) {
        return reservationService.reserveForJob(request, allowPartial);
    }

    @Override
    public ReservationResponse reserveForJobRequirements(UUID jobId, boolean allowPartial) {
        return reservationService.reserveForJobRequirements(jobId, allowPartial);
    }

    @Override
    public CustomerPoReservationResponse reserveForCustomerPo(
            CustomerPoReservationRequest request, boolean allowPartial) {
        return customerPoReservationService.reserve(request, allowPartial);
    }

    @Override
    public in.boss.inventory.api.InventoryApiDtos.FeasibilityResponse checkAndReserveForCustomerPo(UUID cpoId) {
        return feasibilityCheckService.checkAndReserve(cpoId);
    }

    @Override
    public void releaseJobReservations(UUID jobId) {
        reservationReleaseService.releaseJobReservations(jobId);
    }

    @Override
    public void releaseCustomerPoReservations(UUID cpoId) {
        customerPoReservationService.release(cpoId);
    }

    @Override
    public StockMovementResponse issueMaterial(IssueMaterialRequest request) {
        return materialIssueService.issueMaterial(request);
    }

    @Override
    public StockMovementResponse issueReservedMaterial(IssueMaterialRequest request) {
        return materialIssueService.issueReservedMaterial(request);
    }

    @Override
    public StockMovementResponse returnMaterial(ReturnMaterialRequest request) {
        return materialIssueService.returnMaterial(request);
    }

    @Override
    public MaterialLotResponse transitionLotStatus(UUID lotId, String targetStatus) {
        BossContext ctx = requireContext();
        LotRow lot = requireVisibleLot(ctx, lotId, true);
        String target = LotStatus.canonical(targetStatus);
        LotStatusMachine.assertExplicitTransition(lot.status(), target, lot.quantity(), lot.reservedQty());
        repository.updateLot(ctx.tenantId(), lot.id(), lot.quantity(), target);
        auditService.record("LOT", lot.id(), "STATUS",
                json(Map.of("status", LotStatus.toApi(lot.status()))),
                json(Map.of("status", LotStatus.toApi(target))));
        return toLot(requireVisibleLot(ctx, lotId, false));
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryPage<InventoryPositionResponse> getPosition(
            UUID storeId, UUID itemId, UUID siteId, boolean belowReorderPoint, int page, int size) {
        BossContext ctx = requireContext();
        checkPage(page, size);
        UUID effectiveSite = effectiveSite(ctx, siteId);
        if (siteId != null && !ctx.canAccessSite(siteId)) {
            return new InventoryPage<>(List.of(), page, 0);
        }
        List<InventoryPositionResponse> content = repository.positions(
                        ctx.tenantId(), itemId, storeId, effectiveSite, belowReorderPoint, page, size)
                .stream()
                .map(row -> new InventoryPositionResponse(
                        row.itemId(), row.itemCode(), row.itemName(), row.storeId(), row.storeName(),
                        row.totalQty(), row.reservedQty(), row.availableQty(), row.inTransitQty(),
                        row.valuationAmt(),
                        row.reorderPoint(), row.uomSymbol(), row.lastMovedAt()))
                .toList();
        long total = repository.countPositions(
                ctx.tenantId(), itemId, storeId, effectiveSite, belowReorderPoint);
        return new InventoryPage<>(content, page, total);
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryPage<MaterialLotResponse> listLots(
            UUID itemId, UUID storeId, String status, int page, int size) {
        BossContext ctx = requireContext();
        checkPage(page, size);
        String persistedStatus = status == null || status.isBlank() ? null : LotStatus.canonical(status);
        UUID siteId = ctx.siteId();
        List<MaterialLotResponse> content = repository.listLots(
                        ctx.tenantId(), itemId, storeId, persistedStatus, siteId, page, size)
                .stream()
                .map(this::toLot)
                .toList();
        return new InventoryPage<>(content, page,
                repository.countLots(ctx.tenantId(), itemId, storeId, persistedStatus, siteId));
    }

    @Override
    @Transactional(readOnly = true)
    public MaterialLotResponse getLot(UUID lotId) {
        return toLot(requireVisibleLot(requireContext(), lotId, false));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LotReservationResponse> getLotReservations(UUID lotId) {
        BossContext ctx = requireContext();
        requireVisibleLot(ctx, lotId, false);
        return reservationRepository.activeLotReservations(ctx.tenantId(), lotId).stream()
                .map(row -> new LotReservationResponse(
                        row.id(), row.referenceType(), row.referenceId(),
                        row.reservedQty(), row.documentNumber()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobReservationResponse> listActiveJobReservations() {
        BossContext ctx = requireContext();
        UUID siteId = ctx.isSiteScoped() ? ctx.siteId() : null;
        return reservationRepository.activeProductionReservations(ctx.tenantId(), siteId).stream()
                .map(row -> new JobReservationResponse(row.jobId(), row.jobNumber(), row.siteId(),
                        row.itemId(), row.itemCode(), row.itemName(), row.reservedQty()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryPage<StockMovementResponse> getLotMovements(
            UUID lotId, LocalDate from, LocalDate to, int page, int size) {
        BossContext ctx = requireContext();
        checkPage(page, size);
        if (from != null && to != null && from.isAfter(to)) {
            throw new InventoryValidationException("Movement from-date is after to-date");
        }
        requireVisibleLot(ctx, lotId, false);
        List<StockMovementResponse> content = repository.listMovements(
                        ctx.tenantId(), lotId, from, to, page, size)
                .stream()
                .map(this::toMovement)
                .toList();
        return new InventoryPage<>(content, page,
                repository.countMovements(ctx.tenantId(), lotId, from, to));
    }

    private UUID appendMovement(BossContext ctx, UUID lotId, UUID itemId, UUID storeId, MovementType type,
                                BigDecimal qty, BigDecimal unitCost, String referenceType, UUID referenceId,
                                String note) {
        UUID movementId = UUID.randomUUID();
        repository.insertMovement(
                movementId,
                ctx.tenantId(),
                type.dbValue(),
                itemId,
                lotId,
                type.inbound() ? null : storeId,
                type.inbound() ? storeId : null,
                qty,
                unitCost,
                referenceType,
                referenceId,
                ctx.userId(),
                note);
        return movementId;
    }

    private LotRow requireVisibleLot(BossContext ctx, UUID lotId, boolean lock) {
        LotRow lot = repository.findLot(ctx.tenantId(), lotId, lock)
                .orElseThrow(() -> new LotNotFoundException(lotId));
        if (!ctx.canAccessSite(lot.siteId())) {
            throw new LotNotFoundException(lotId);
        }
        return lot;
    }

    private void requireGrnLine(UUID tenantId, UUID grnLineId, UUID itemId, UUID storeId) {
        if (grnLineId == null) {
            return;
        }
        GrnLineRef line = repository.findGrnLine(tenantId, grnLineId)
                .orElseThrow(() -> new InventoryValidationException(
                        "GRN line not found for this tenant: " + grnLineId));
        if (!itemId.equals(line.itemId()) || !storeId.equals(line.storeId())) {
            throw new InventoryValidationException(
                    "GRN line does not match the lot item and store");
        }
    }

    private void requireItem(UUID tenantId, UUID itemId) {
        if (!repository.itemExists(tenantId, itemId)) {
            throw new InventoryValidationException("Item not found for this tenant: " + itemId);
        }
    }

    private UUID requireStore(BossContext ctx, UUID storeId) {
        UUID siteId = repository.storeSiteId(ctx.tenantId(), storeId)
                .orElseThrow(() -> new InventoryValidationException(
                        "Store not found for this tenant: " + storeId));
        if (!ctx.canAccessSite(siteId)) {
            throw new InventoryValidationException("Store is outside the caller's site");
        }
        return siteId;
    }

    private static UUID effectiveSite(BossContext ctx, UUID requestedSiteId) {
        return ctx.siteId() != null ? ctx.siteId() : requestedSiteId;
    }

    private static void checkPage(int page, int size) {
        if (page < 0) {
            throw new InventoryValidationException("Page must be zero or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InventoryValidationException("Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private static BigDecimal quantity(BigDecimal value) {
        BigDecimal scaled = value.setScale(3, RoundingMode.HALF_UP);
        if (scaled.compareTo(MIN_QTY) < 0) {
            throw new InventoryValidationException("Quantity must be at least 0.001");
        }
        return scaled;
    }

    private static BigDecimal unitCost(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw new InventoryValidationException("Unit cost cannot be negative");
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private MaterialLotResponse toLot(LotRow lot) {
        return new MaterialLotResponse(
                lot.id(),
                lot.itemId(),
                lot.itemCode(),
                lot.itemName(),
                lot.storeId(),
                lot.storeName(),
                lot.siteId(),
                lot.batchNumber(),
                lot.lotNumber(),
                lot.grnLineId(),
                lot.quantity(),
                lot.reservedQty(),
                LotStatus.QUARANTINE.equals(lot.status()) ? BigDecimal.ZERO : lot.quantity().subtract(lot.reservedQty()),
                lot.unitCost(),
                lot.uomId(),
                lot.uomSymbol(),
                LotStatus.toApi(lot.status()),
                lot.expiryDate(),
                lot.createdAt());
    }

    private StockMovementResponse toMovement(MovementRow row) {
        MovementType type = MovementType.parse(row.movementType());
        BigDecimal signed = type.inbound() ? row.quantity() : row.quantity().negate();
        return new StockMovementResponse(
                row.id(),
                row.lotId(),
                row.movementType(),
                signed,
                row.referenceType(),
                row.referenceId(),
                row.performedBy(),
                row.notes(),
                row.createdAt());
    }

    private static Map<String, Object> lotSnapshot(LotRow lot) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", lot.id());
        snapshot.put("itemId", lot.itemId());
        snapshot.put("storeId", lot.storeId());
        snapshot.put("qty", lot.quantity());
        snapshot.put("status", LotStatus.toApi(lot.status()));
        return snapshot;
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
