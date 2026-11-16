package in.boss.masterdata.domain.impl;

import in.boss.masterdata.api.dto.BOMExplosionMaterialDto;
import in.boss.masterdata.api.dto.BOMExplosionResultDto;
import in.boss.masterdata.domain.BomExplosionService;
import in.boss.masterdata.domain.CircularReferenceException;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.MaxBomLevelExceededException;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
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
        UUID tenantId = getTenantId();

        itemRepository.findByIdAndTenantId(itemId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Item not found: " + itemId));

        BigDecimal topQty = (quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0)
                ? quantity
                : BigDecimal.ONE;

        List<RawExplosionLine> rawResults = new ArrayList<>();
        Set<UUID> path = new HashSet<>();
        path.add(itemId);

        explodeRecursive(tenantId, itemId, topQty, 1, path, rawResults);

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
                    .qty(raw.qty().setScale(4, RoundingMode.HALF_UP))
                    .uomSymbol(uom != null ? uom.getSymbol() : null)
                    .level(raw.level())
                    .build());
        }

        return BOMExplosionResultDto.builder()
                .itemId(itemId)
                .flatMaterials(flatMaterials)
                .build();
    }

    private void explodeRecursive(UUID tenantId,
                                  UUID currentItemId,
                                  BigDecimal currentQty,
                                  int currentLevel,
                                  Set<UUID> path,
                                  List<RawExplosionLine> results) {
        if (currentLevel > 5) {
            throw new MaxBomLevelExceededException("BOM explosion exceeded max allowed level of 5 at item: " + currentItemId);
        }

        Optional<BomHeaderEntity> activeBomOpt = bomHeaderRepository
                .findByTenantIdAndFinishedItemIdAndStatus(tenantId, currentItemId, "approved");

        if (activeBomOpt.isEmpty()) {
            return;
        }

        List<BomLineEntity> lines = bomLineRepository
                .findByTenantIdAndBomHeaderIdOrderByLevelAscCreatedAtAsc(tenantId, activeBomOpt.get().getId());

        for (BomLineEntity line : lines) {
            UUID compId = line.getComponentItemId();

            // Cycle Guard
            if (path.contains(compId)) {
                throw new CircularReferenceException("Cycle guard detected infinite loop in BOM at item: " + compId);
            }

            BigDecimal scrapMultiplier = BigDecimal.ONE.add(
                    line.getScrapPercent().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
            BigDecimal lineQty = line.getQuantity().multiply(currentQty).multiply(scrapMultiplier);

            Optional<BomHeaderEntity> childBomOpt = bomHeaderRepository
                    .findByTenantIdAndFinishedItemIdAndStatus(tenantId, compId, "approved");

            if (childBomOpt.isPresent()) {
                // Semi-finished item with child BOM -> recurse deeper
                path.add(compId);
                explodeRecursive(tenantId, compId, lineQty, currentLevel + 1, path, results);
                path.remove(compId);
            } else {
                // Leaf raw material
                results.add(new RawExplosionLine(compId, lineQty, line.getUomId(), currentLevel));
            }
        }
    }

    private record RawExplosionLine(UUID itemId, BigDecimal qty, UUID uomId, int level) {}
}
