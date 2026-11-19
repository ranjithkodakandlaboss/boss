package in.boss.masterdata.domain.impl;

import in.boss.masterdata.api.dto.BOMExplosionMaterialDto;
import in.boss.masterdata.api.dto.BOMExplosionResultDto;
import in.boss.masterdata.api.dto.BOMRevisionSnapshotDto;
import in.boss.masterdata.domain.BomQuantityFormula;
import in.boss.masterdata.domain.BomExplosionService;
import in.boss.masterdata.domain.CircularReferenceException;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.MaxBomLevelExceededException;
import in.boss.masterdata.domain.ValidationException;
import in.boss.masterdata.persistence.BomHeaderEntity;
import in.boss.masterdata.persistence.BomHeaderRepository;
import in.boss.masterdata.persistence.BomLineEntity;
import in.boss.masterdata.persistence.BomLineRepository;
import in.boss.masterdata.persistence.ItemEntity;
import in.boss.masterdata.persistence.ItemRepository;
import in.boss.masterdata.persistence.UnitOfMeasureEntity;
import in.boss.masterdata.persistence.UnitOfMeasureRepository;
import in.boss.shared.context.BossContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true, noRollbackFor = {
        ValidationException.class, CircularReferenceException.class, MaxBomLevelExceededException.class
})
public class BomExplosionServiceImpl implements BomExplosionService {

    private final BomHeaderRepository bomHeaderRepository;
    private final BomLineRepository bomLineRepository;
    private final ItemRepository itemRepository;
    private final UnitOfMeasureRepository uomRepository;

    public BomExplosionServiceImpl(BomHeaderRepository bomHeaderRepository,
                                  BomLineRepository bomLineRepository,
                                  ItemRepository itemRepository,
                                  UnitOfMeasureRepository uomRepository) {
        this.bomHeaderRepository = bomHeaderRepository;
        this.bomLineRepository = bomLineRepository;
        this.itemRepository = itemRepository;
        this.uomRepository = uomRepository;
    }

    private UUID getTenantId() {
        var ctx = BossContextHolder.get();
        if (ctx == null || ctx.tenantId() == null) {
            throw new IllegalStateException("Tenant context is required");
        }
        return ctx.tenantId();
    }

    @Override
    public BOMExplosionResultDto explodeBOM(UUID itemId, BigDecimal quantity) {
        return explode(itemId, quantity, Map.of(), false);
    }

    @Override
    public BOMExplosionResultDto explodeBOM(UUID itemId, BigDecimal quantity,
                                            Map<String, BigDecimal> specifications) {
        return explode(itemId, quantity, specifications, false);
    }

    @Override
    public BOMExplosionResultDto explodeBOMExact(UUID itemId, BigDecimal quantity) {
        return explode(itemId, quantity, Map.of(), true);
    }

    private BOMExplosionResultDto explode(UUID itemId, BigDecimal quantity,
                                          Map<String, BigDecimal> specifications, boolean exact) {
        UUID tenantId = getTenantId();
        Map<String, BigDecimal> safeSpecifications = validateSpecifications(specifications);

        itemRepository.findByIdAndTenantId(itemId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Item not found: " + itemId));

        BigDecimal topQty = (quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0)
                ? quantity
                : BigDecimal.ONE;

        List<RawExplosionLine> rawResults = new ArrayList<>();
        Set<UUID> path = new HashSet<>();
        path.add(itemId);
        Map<UUID, BOMRevisionSnapshotDto> bomRevisions = new LinkedHashMap<>();

        explodeRecursive(tenantId, itemId, topQty, 1, path, rawResults, safeSpecifications, bomRevisions);

        // Fetch details for all leaf materials
        Set<UUID> leafItemIds = rawResults.stream().map(RawExplosionLine::itemId).collect(Collectors.toSet());
        Map<UUID, ItemEntity> itemMap = itemRepository.findAllById(leafItemIds).stream()
                .collect(Collectors.toMap(ItemEntity::getId, Function.identity()));

        Set<UUID> uomIds = rawResults.stream().map(RawExplosionLine::uomId).collect(Collectors.toSet());
        Map<UUID, UnitOfMeasureEntity> uomMap = uomRepository.findAllById(uomIds).stream()
                .collect(Collectors.toMap(UnitOfMeasureEntity::getId, Function.identity()));

        List<BOMExplosionMaterialDto> flatMaterials = new ArrayList<>();
        for (RawExplosionLine raw : rawResults) {
            ItemEntity item = itemMap.get(raw.itemId());
            UnitOfMeasureEntity uom = uomMap.get(raw.uomId());

            flatMaterials.add(BOMExplosionMaterialDto.builder()
                    .itemId(raw.itemId())
                    .itemCode(item != null ? item.getCode() : null)
                    .itemName(item != null ? item.getName() : null)
                    .qty(exact ? raw.qty() : raw.qty().setScale(4, RoundingMode.HALF_UP))
                    .uomId(raw.uomId())
                    .uomSymbol(uom != null ? uom.getSymbol() : null)
                    .level(raw.level())
                    .build());
        }

        List<BOMRevisionSnapshotDto> revisions = List.copyOf(bomRevisions.values());
        BOMRevisionSnapshotDto rootRevision = revisions.isEmpty() ? null : revisions.get(0);
        return BOMExplosionResultDto.builder()
                .itemId(itemId)
                .bomHeaderId(rootRevision == null ? null : rootRevision.bomHeaderId())
                .bomVersion(rootRevision == null ? null : rootRevision.version())
                .quantity(topQty)
                .specifications(safeSpecifications)
                .bomRevisions(revisions)
                .flatMaterials(flatMaterials)
                .build();
    }

    @Override
    public List<String> requiredSpecificationNames(UUID itemId) {
        UUID tenantId = getTenantId();
        itemRepository.findByIdAndTenantId(itemId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Item not found: " + itemId));
        Set<String> names = new LinkedHashSet<>();
        Set<UUID> path = new HashSet<>();
        path.add(itemId);
        collectSpecificationNames(tenantId, itemId, 1, path, names);
        return List.copyOf(names);
    }

    private void collectSpecificationNames(UUID tenantId, UUID itemId, int level,
                                           Set<UUID> path, Set<String> names) {
        if (level > 5) {
            throw new MaxBomLevelExceededException("BOM specification discovery exceeded max allowed level of 5");
        }
        Optional<BomHeaderEntity> activeBom = bomHeaderRepository
                .findByTenantIdAndFinishedItemIdAndStatus(tenantId, itemId, "approved");
        if (activeBom.isEmpty()) {
            return;
        }
        List<BomLineEntity> lines = bomLineRepository.findByTenantIdAndBomHeaderIdOrderByLevelAscCreatedAtAsc(
                tenantId, activeBom.get().getId());
        for (BomLineEntity line : lines) {
            String formula = line.getQuantityFormula();
            if (formula != null && !formula.isBlank()) {
                try {
                    names.addAll(BomQuantityFormula.variables(formula));
                } catch (IllegalArgumentException exception) {
                    throw new ValidationException("Invalid BOM quantity formula: " + exception.getMessage());
                }
            }
            UUID componentId = line.getComponentItemId();
            Optional<BomHeaderEntity> childBom = bomHeaderRepository
                    .findByTenantIdAndFinishedItemIdAndStatus(tenantId, componentId, "approved");
            if (childBom.isPresent()) {
                if (!path.add(componentId)) {
                    throw new CircularReferenceException("Cycle detected while reading BOM specification keys");
                }
                collectSpecificationNames(tenantId, componentId, level + 1, path, names);
                path.remove(componentId);
            }
        }
    }

    private void explodeRecursive(UUID tenantId,
                                  UUID currentItemId,
                                  BigDecimal currentQty,
                                  int currentLevel,
                                  Set<UUID> path,
                                  List<RawExplosionLine> results,
                                  Map<String, BigDecimal> specifications,
                                  Map<UUID, BOMRevisionSnapshotDto> bomRevisions) {
        if (currentLevel > 5) {
            throw new MaxBomLevelExceededException("BOM explosion exceeded max allowed level of 5 at item: " + currentItemId);
        }

        Optional<BomHeaderEntity> activeBomOpt = bomHeaderRepository
                .findByTenantIdAndFinishedItemIdAndStatus(tenantId, currentItemId, "approved");

        if (activeBomOpt.isEmpty()) {
            return;
        }
        BomHeaderEntity activeBom = activeBomOpt.get();
        bomRevisions.putIfAbsent(currentItemId,
                new BOMRevisionSnapshotDto(currentItemId, activeBom.getId(), activeBom.getVersion()));

        List<BomLineEntity> lines = bomLineRepository
                .findByTenantIdAndBomHeaderIdOrderByLevelAscCreatedAtAsc(tenantId, activeBom.getId());

        for (BomLineEntity line : lines) {
            UUID compId = line.getComponentItemId();

            // Cycle Guard
            if (path.contains(compId)) {
                throw new CircularReferenceException("Cycle guard detected infinite loop in BOM at item: " + compId);
            }

            BigDecimal perUnitQuantity = line.getQuantity();
            if (line.getQuantityFormula() != null && !line.getQuantityFormula().isBlank()) {
                try {
                    perUnitQuantity = BomQuantityFormula.evaluate(line.getQuantityFormula(), specifications);
                } catch (IllegalArgumentException exception) {
                    throw new ValidationException("Cannot calculate BOM line for item " + currentItemId
                            + ": " + exception.getMessage());
                }
                if (perUnitQuantity.signum() <= 0) {
                    throw new ValidationException("BOM quantity formula must produce a value greater than zero");
                }
            }
            BigDecimal scrapMultiplier = BigDecimal.ONE.add(
                    line.getScrapPercent().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
            BigDecimal lineQty = perUnitQuantity.multiply(currentQty).multiply(scrapMultiplier);

            Optional<BomHeaderEntity> childBomOpt = bomHeaderRepository
                    .findByTenantIdAndFinishedItemIdAndStatus(tenantId, compId, "approved");

            if (childBomOpt.isPresent()) {
                // Semi-finished item with child BOM -> recurse deeper
                path.add(compId);
                explodeRecursive(tenantId, compId, lineQty, currentLevel + 1, path, results,
                        specifications, bomRevisions);
                path.remove(compId);
            } else {
                // Leaf raw material
                results.add(new RawExplosionLine(compId, lineQty, line.getUomId(), currentLevel));
            }
        }
    }

    private static Map<String, BigDecimal> validateSpecifications(Map<String, BigDecimal> specifications) {
        if (specifications == null || specifications.isEmpty()) {
            return Map.of();
        }
        if (specifications.size() > 30) {
            throw new ValidationException("At most 30 specification values can be supplied");
        }
        for (Map.Entry<String, BigDecimal> entry : specifications.entrySet()) {
            String name = entry.getKey();
            if (name == null || !name.matches("[a-z][a-z0-9_]*")) {
                throw new ValidationException("Specification names must be valid identifiers");
            }
            if (entry.getValue() == null || entry.getValue().signum() < 0) {
                throw new ValidationException("Specification values must be zero or greater");
            }
        }
        return Map.copyOf(specifications);
    }

    private record RawExplosionLine(UUID itemId, BigDecimal qty, UUID uomId, int level) {}
}
