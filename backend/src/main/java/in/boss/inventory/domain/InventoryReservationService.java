package in.boss.inventory.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.inventory.api.InventoryApiDtos.ReservationAllocation;
import in.boss.inventory.api.InventoryApiDtos.ReservationLineResponse;
import in.boss.inventory.api.InventoryApiDtos.ReservationRequest;
import in.boss.inventory.api.InventoryApiDtos.ReservationResponse;
import in.boss.inventory.persistence.InventoryReservationRepository;
import in.boss.inventory.persistence.InventoryReservationRepository.JobMaterialSnapshotLine;
import in.boss.inventory.persistence.LotRow;
import in.boss.inventory.events.InventoryMetrics;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import in.boss.masterdata.domain.BomExplosionService;
import in.boss.masterdata.api.dto.BOMExplosionMaterialDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class InventoryReservationService {

    private static final BigDecimal MIN_QTY = new BigDecimal("0.001");

    private final InventoryReservationRepository reservationRepository;
    private final BomExplosionService bomExplosionService;
    private final InventoryReservationPlanner planner;
    private final InventoryReplenishmentService replenishmentService;
    private final InventoryFeatureFlags featureFlags;
    private final InventoryMetrics metrics;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public InventoryReservationService(InventoryReservationRepository reservationRepository,
                                       InventoryReservationPlanner planner,
                                       BomExplosionService bomExplosionService,
                                       InventoryReplenishmentService replenishmentService,
                                       InventoryFeatureFlags featureFlags,
                                       InventoryMetrics metrics,
                                       AuditService auditService,
                                       ObjectMapper objectMapper) {
        this.reservationRepository = reservationRepository;
        this.planner = planner;
        this.bomExplosionService = bomExplosionService;
        this.replenishmentService = replenishmentService;
        this.featureFlags = featureFlags;
        this.metrics = metrics;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public ReservationResponse reserveForJob(ReservationRequest request, boolean allowPartial) {
        BossContext ctx = requireContext();
        featureFlags.require(ctx.tenantId(), InventoryFeatureFlags.JOB_RESERVATIONS);
        requireLockedJob(ctx, request.jobId());
        if (reservationRepository.hasActiveReservations(ctx.tenantId(), request.jobId())) {
            throw new ReservationConflictException(
                    "This production job already has active material reservations");
        }

        List<ReservationPlan> plans = planner.planRequirements(ctx, request.requirements());
        return persistPlans(ctx, request.jobId(), plans, allowPartial);
    }

    public ReservationResponse reserveForJobRequirements(UUID jobId, boolean allowPartial) {
        BossContext ctx = requireContext();
        featureFlags.require(ctx.tenantId(), InventoryFeatureFlags.JOB_RESERVATIONS);
        InventoryReservationRepository.ProductionJobSnapshot job =
                reservationRepository.lockProductionJobSnapshot(ctx.tenantId(), jobId)
                        .orElseThrow(() -> new InventoryValidationException(
                                "Production job not found for this tenant"));
        if (!ctx.canAccessSite(job.siteId())) {
            throw new InventoryValidationException("Production job not found for this tenant");
        }
        if (job.status() != null && TERMINAL_JOB_STATUSES.contains(job.status().toLowerCase(java.util.Locale.ROOT))) {
            throw new InventoryValidationException(
                    "Stock cannot be reserved for a production job in status '" + job.status() + "'");
        }
        if (reservationRepository.hasActiveReservations(ctx.tenantId(), jobId)) {
            throw new ReservationConflictException(
                    "This production job already has active material reservations");
        }
        ensureMaterialSnapshot(ctx, jobId, job);
        Map<UUID, BigDecimal> requestedByItem = new LinkedHashMap<>();
        for (InventoryReservationRepository.JobMaterialNeed need
                : reservationRepository.lockUnissuedJobMaterials(ctx.tenantId(), jobId)) {
            requestedByItem.merge(need.itemId(), quantity(need.remainingQty()), BigDecimal::add);
        }
        if (requestedByItem.isEmpty()) {
            throw new InventoryValidationException(
                    "The production job has no unissued BOM material requirements");
        }
        List<ReservationPlan> plans = planner.planSiteRequirements(ctx, job.siteId(), requestedByItem);
        return persistPlans(ctx, jobId, plans, allowPartial);
    }

    private void ensureMaterialSnapshot(
            BossContext context, UUID jobId,
            InventoryReservationRepository.ProductionJobSnapshot job) {
        if (reservationRepository.hasJobMaterialRequirements(context.tenantId(), jobId)) {
            return;
        }
        List<BOMExplosionMaterialDto> materials =
                bomExplosionService.explodeBOM(job.finishedItemId(), job.quantity()).getFlatMaterials();
        if (materials == null || materials.isEmpty()) {
            throw new InventoryValidationException(
                    "The production item's approved BOM has no reservable material requirements");
        }
        List<JobMaterialSnapshotLine> lines = materials.stream()
                .map(this::snapshotLine)
                .toList();
        reservationRepository.insertJobMaterialRequirements(context.tenantId(), jobId, lines);
    }

    private JobMaterialSnapshotLine snapshotLine(BOMExplosionMaterialDto material) {
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

    private ReservationResponse persistPlans(BossContext ctx, UUID jobId,
                                             List<ReservationPlan> plans, boolean allowPartial) {
        if (!allowPartial && plans.stream().anyMatch(plan -> plan.shortfall().signum() > 0)) {
            metrics.reservation("failed");
            return failedReservation(jobId, plans);
        }

        List<ReservationLineResponse> results = plans.stream()
                .map(plan -> persistPlan(ctx, jobId, plan))
                .toList();
        plans.stream()
                .flatMap(plan -> plan.allocations().stream()
                        .map(allocation -> new ItemStore(plan.itemId(), allocation.lot().storeId())))
                .distinct()
                .forEach(key -> replenishmentService.checkAfterStockChange(
                        ctx.tenantId(), key.itemId(), key.storeId()));
        boolean fullyReserved = results.stream().allMatch(line -> line.shortfall().signum() == 0);
        boolean anyReserved = results.stream().anyMatch(line -> line.reserved().signum() > 0);
        String status = fullyReserved ? "FULLY_RESERVED" : anyReserved ? "PARTIALLY_RESERVED" : "FAILED";
        metrics.reservation(status.toLowerCase());
        return new ReservationResponse(jobId, status, results);
    }

    private ReservationLineResponse persistPlan(BossContext ctx, UUID jobId, ReservationPlan plan) {
        List<ReservationAllocation> allocations = new ArrayList<>();
        for (PlannedLot planned : plan.allocations()) {
            LotRow lot = planned.lot();
            BigDecimal newReserved = lot.reservedQty().add(planned.quantity());
            String nextStatus = newReserved.compareTo(lot.quantity()) == 0
                    ? LotStatus.RESERVED : LotStatus.AVAILABLE;
            reservationRepository.insertReservation(ctx.tenantId(), lot.id(), lot.itemId(),
                    planned.quantity(), jobId);
            reservationRepository.updateLotBalance(ctx.tenantId(), lot.id(), lot.quantity(),
                    newReserved, nextStatus);
            allocations.add(new ReservationAllocation(lot.id(), planned.quantity()));
            Map<String, Object> snapshot = Map.of(
                    "jobId", jobId,
                    "lotId", lot.id(),
                    "itemId", lot.itemId(),
                    "reservedQty", planned.quantity());
            auditService.record("STOCK_RESERVATION", lot.id(), "CREATE", null, json(snapshot));
        }
        return new ReservationLineResponse(plan.itemId(), plan.storeId(), plan.requested(),
                plan.reserved(), plan.shortfall(), List.copyOf(allocations));
    }

    private static ReservationResponse failedReservation(UUID jobId, List<ReservationPlan> plans) {
        List<ReservationLineResponse> lines = plans.stream()
                .map(plan -> new ReservationLineResponse(plan.itemId(), plan.storeId(),
                        plan.requested(), BigDecimal.ZERO.setScale(3), plan.requested(), List.of()))
                .toList();
        return new ReservationResponse(jobId, "FAILED", lines);
    }

    private static final java.util.Set<String> TERMINAL_JOB_STATUSES = java.util.Set.of("completed", "closed", "cancelled");

    private UUID requireLockedJob(BossContext ctx, UUID jobId) {
        InventoryReservationRepository.JobLock lock = reservationRepository.lockProductionJob(ctx.tenantId(), jobId)
                .orElseThrow(() -> new InventoryValidationException(
                        "Production job not found for this tenant"));
        if (!ctx.canAccessSite(lock.siteId())) {
            throw new InventoryValidationException("Production job not found for this tenant");
        }
        if (lock.status() != null && TERMINAL_JOB_STATUSES.contains(lock.status().toLowerCase(java.util.Locale.ROOT))) {
            throw new InventoryValidationException(
                    "Stock cannot be reserved for a production job in status '" + lock.status() + "'");
        }
        return lock.siteId();
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
