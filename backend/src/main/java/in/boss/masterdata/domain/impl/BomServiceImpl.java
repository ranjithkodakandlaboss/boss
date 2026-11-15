package in.boss.masterdata.domain.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.masterdata.api.dto.BOMHeaderDto;
import in.boss.masterdata.api.dto.BOMLineDto;
import in.boss.masterdata.api.dto.CreateBOMLineRequest;
import in.boss.masterdata.api.dto.CreateBOMRequest;
import in.boss.masterdata.domain.BomService;
import in.boss.masterdata.domain.CircularReferenceException;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.MaxBomLevelExceededException;
import in.boss.masterdata.domain.ValidationException;
import in.boss.masterdata.events.MasterDataEventPublisher;
import in.boss.masterdata.persistence.BomHeaderEntity;
import in.boss.masterdata.persistence.BomHeaderRepository;
import in.boss.masterdata.persistence.BomLineEntity;
import in.boss.masterdata.persistence.BomLineRepository;
import in.boss.masterdata.persistence.ItemEntity;
import in.boss.masterdata.persistence.ItemRepository;
import in.boss.masterdata.persistence.UnitOfMeasureEntity;
import in.boss.masterdata.persistence.UnitOfMeasureRepository;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
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
@Transactional
public class BomServiceImpl implements BomService {

    private final BomHeaderRepository bomHeaderRepository;
    private final BomLineRepository bomLineRepository;
    private final ItemRepository itemRepository;
    private final UnitOfMeasureRepository uomRepository;
    private final AuditService auditService;
    private final MasterDataEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public BomServiceImpl(BomHeaderRepository bomHeaderRepository,
                          BomLineRepository bomLineRepository,
                          ItemRepository itemRepository,
                          UnitOfMeasureRepository uomRepository,
                          AuditService auditService,
                          MasterDataEventPublisher eventPublisher,
                          ObjectMapper objectMapper) {
        this.bomHeaderRepository = bomHeaderRepository;
        this.bomLineRepository = bomLineRepository;
        this.itemRepository = itemRepository;
        this.uomRepository = uomRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    private UUID getTenantId() {
        var ctx = BossContextHolder.get();
        if (ctx == null || ctx.tenantId() == null) {
            throw new IllegalStateException("Tenant context is required");
        }
        return ctx.tenantId();
    }

    @Override
    @Transactional(readOnly = true)
    public BOMHeaderDto getItemBOM(UUID itemId) {
        UUID tenantId = getTenantId();

        ItemEntity item = itemRepository.findByIdAndTenantId(itemId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Item not found: " + itemId));

        BomHeaderEntity header = bomHeaderRepository
                .findByTenantIdAndFinishedItemIdAndStatus(tenantId, itemId, "approved")
                .orElseThrow(() -> new EntityNotFoundException("No active BOM for item: " + itemId));

        return buildHeaderDto(header, item, tenantId);
    }

    @Override
    public BOMHeaderDto createOrReviseBOM(UUID itemId, CreateBOMRequest request) {
        UUID tenantId = getTenantId();

        ItemEntity item = itemRepository.findByIdAndTenantId(itemId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Item not found: " + itemId));

        if (!item.isActive()) {
            throw new ValidationException("Cannot create BOM for inactive item: " + itemId);
        }

        if (request.getLines() == null || request.getLines().isEmpty()) {
            throw new ValidationException("BOM must contain at least one line");
        }

        // Validate each line and check for direct circular references
        for (CreateBOMLineRequest line : request.getLines()) {
            if (line.getComponentItemId() == null) {
                throw new ValidationException("Component item ID is required");
            }
            if (line.getComponentItemId().equals(itemId)) {
                throw new CircularReferenceException("Item cannot reference itself in BOM: " + itemId);
            }
            if (line.getQty() == null || line.getQty().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ValidationException("BOM line quantity must be greater than 0");
            }
            if (line.getScrapFactor() != null) {
                if (line.getScrapFactor().compareTo(BigDecimal.ZERO) < 0 ||
                    line.getScrapFactor().compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new ValidationException("Scrap factor must be between 0 and 100");
                }
            }
            if (line.getLevel() != null && (line.getLevel() < 1 || line.getLevel() > 5)) {
                throw new MaxBomLevelExceededException("BOM level must be between 1 and 5, received: " + line.getLevel());
            }

            ItemEntity compItem = itemRepository.findByIdAndTenantId(line.getComponentItemId(), tenantId)
                    .orElseThrow(() -> new ValidationException("Component item not found: " + line.getComponentItemId()));
            if (!compItem.isActive()) {
                throw new ValidationException("Component item is inactive: " + line.getComponentItemId());
            }

            uomRepository.findByIdAndTenantId(line.getUomId(), tenantId)
                    .orElseThrow(() -> new ValidationException("Invalid UOM ID: " + line.getUomId()));
        }

        // Circular reference and depth validation across descendant BOMs
        for (CreateBOMLineRequest line : request.getLines()) {
            checkCircularReference(tenantId, line.getComponentItemId(), itemId, new HashSet<>());
            int subDepth = computeMaxSubDepth(tenantId, line.getComponentItemId(), new HashSet<>());
            int declaredLevel = line.getLevel() != null ? line.getLevel() : 1;
            if (declaredLevel + subDepth - 1 > 5) {
                throw new MaxBomLevelExceededException("BOM exceeds maximum allowed nesting level of 5. Sub-tree depth: " + (declaredLevel + subDepth - 1));
            }
        }

        // Check if an active BOM already exists
        Optional<BomHeaderEntity> existingActiveOpt = bomHeaderRepository
                .findByTenantIdAndFinishedItemIdAndStatus(tenantId, itemId, "approved");

        int nextVersionNumber = 1;
        if (existingActiveOpt.isPresent()) {
            BomHeaderEntity prev = existingActiveOpt.get();
            prev.setStatus("superseded");
            prev.setDefault(false);
            prev.setUpdatedAt(Instant.now());
            bomHeaderRepository.save(prev);

            try {
                String cleanVer = prev.getVersion().replaceAll("[^0-9]", "");
                if (!cleanVer.isEmpty()) {
                    nextVersionNumber = Integer.parseInt(cleanVer) + 1;
                }
            } catch (NumberFormatException ignored) {
                nextVersionNumber = 2;
            }
        }

        Instant now = Instant.now();
        UUID newHeaderId = UUID.randomUUID();
        BomHeaderEntity newHeader = BomHeaderEntity.builder()
                .id(newHeaderId)
                .tenantId(tenantId)
                .finishedItemId(itemId)
                .version("v" + nextVersionNumber)
                .bomType("production")
                .isDefault(true)
                .status("approved")
                .createdAt(now)
                .updatedAt(now)
                .build();

        BomHeaderEntity savedHeader = bomHeaderRepository.save(newHeader);

        List<BomLineEntity> savedLines = new ArrayList<>();
        int seq = 1;
        for (CreateBOMLineRequest lineReq : request.getLines()) {
            BomLineEntity lineEntity = BomLineEntity.builder()
                    .id(UUID.randomUUID())
                    .tenantId(tenantId)
                    .bomHeaderId(savedHeader.getId())
                    .componentItemId(lineReq.getComponentItemId())
                    .quantity(lineReq.getQty())
                    .uomId(lineReq.getUomId())
                    .level(lineReq.getLevel() != null ? lineReq.getLevel() : 1)
                    .scrapPercent(lineReq.getScrapFactor() != null ? lineReq.getScrapFactor() : BigDecimal.ZERO)
                    .isOptional(false)
                    .createdAt(now)
                    .build();

            savedLines.add(bomLineRepository.save(lineEntity));
            seq++;
        }

        BOMHeaderDto dto = buildHeaderDto(savedHeader, item, savedLines, tenantId);

        String action = existingActiveOpt.isPresent() ? "REVISE" : "CREATE";
        auditService.record("BOM", savedHeader.getId(), action, null, toJson(dto));
        eventPublisher.publishEvent("BOM", savedHeader.getId(), action.equals("REVISE") ? "BOM_REVISED" : "BOM_CREATED", dto);

        return dto;
    }

    private void checkCircularReference(UUID tenantId, UUID currentItem, UUID rootItem, Set<UUID> visited) {
        if (currentItem.equals(rootItem)) {
            throw new CircularReferenceException("Circular reference detected: item " + rootItem + " is referenced in descendant BOM");
        }
        if (visited.contains(currentItem)) {
            return;
        }
        visited.add(currentItem);

        Optional<BomHeaderEntity> activeBomOpt = bomHeaderRepository
                .findByTenantIdAndFinishedItemIdAndStatus(tenantId, currentItem, "approved");
        if (activeBomOpt.isPresent()) {
            List<BomLineEntity> lines = bomLineRepository.findByTenantIdAndBomHeaderId(tenantId, activeBomOpt.get().getId());
            for (BomLineEntity line : lines) {
                checkCircularReference(tenantId, line.getComponentItemId(), rootItem, visited);
            }
        }
    }

    private int computeMaxSubDepth(UUID tenantId, UUID currentItem, Set<UUID> path) {
        if (path.contains(currentItem)) {
            throw new CircularReferenceException("Circular reference detected in component hierarchy: " + currentItem);
        }
        path.add(currentItem);

        Optional<BomHeaderEntity> activeBomOpt = bomHeaderRepository
                .findByTenantIdAndFinishedItemIdAndStatus(tenantId, currentItem, "approved");
        if (activeBomOpt.isEmpty()) {
            path.remove(currentItem);
            return 1;
        }

        List<BomLineEntity> lines = bomLineRepository.findByTenantIdAndBomHeaderId(tenantId, activeBomOpt.get().getId());
        int maxChildDepth = 0;
        for (BomLineEntity line : lines) {
            int d = computeMaxSubDepth(tenantId, line.getComponentItemId(), path);
            maxChildDepth = Math.max(maxChildDepth, d);
        }

        path.remove(currentItem);
        return 1 + maxChildDepth;
    }

    private BOMHeaderDto buildHeaderDto(BomHeaderEntity header, ItemEntity item, UUID tenantId) {
        List<BomLineEntity> lines = bomLineRepository
                .findByTenantIdAndBomHeaderIdOrderByLevelAscCreatedAtAsc(tenantId, header.getId());
        return buildHeaderDto(header, item, lines, tenantId);
    }

    private BOMHeaderDto buildHeaderDto(BomHeaderEntity header, ItemEntity item, List<BomLineEntity> lines, UUID tenantId) {
        Set<UUID> componentItemIds = lines.stream().map(BomLineEntity::getComponentItemId).collect(Collectors.toSet());
        Map<UUID, ItemEntity> componentItems = itemRepository.findAllById(componentItemIds).stream()
                .collect(Collectors.toMap(ItemEntity::getId, Function.identity()));

        Set<UUID> uomIds = lines.stream().map(BomLineEntity::getUomId).collect(Collectors.toSet());
        Map<UUID, UnitOfMeasureEntity> uoms = uomRepository.findAllById(uomIds).stream()
                .collect(Collectors.toMap(UnitOfMeasureEntity::getId, Function.identity()));

        int seq = 1;
        List<BOMLineDto> lineDtos = new ArrayList<>();
        for (BomLineEntity line : lines) {
            ItemEntity comp = componentItems.get(line.getComponentItemId());
            UnitOfMeasureEntity uom = uoms.get(line.getUomId());

            lineDtos.add(BOMLineDto.builder()
                    .id(line.getId())
                    .seq(seq++)
                    .componentItemId(line.getComponentItemId())
                    .componentItemCode(comp != null ? comp.getCode() : null)
                    .componentItemName(comp != null ? comp.getName() : null)
                    .qty(line.getQuantity())
                    .uomId(line.getUomId())
                    .uomSymbol(uom != null ? uom.getSymbol() : null)
                    .scrapFactor(line.getScrapPercent())
                    .level(line.getLevel())
                    .build());
        }

        int versionInt = 1;
        try {
            String clean = header.getVersion().replaceAll("[^0-9]", "");
            if (!clean.isEmpty()) {
                versionInt = Integer.parseInt(clean);
            }
        } catch (NumberFormatException ignored) {}

        return BOMHeaderDto.builder()
                .id(header.getId())
                .itemId(item.getId())
                .itemCode(item.getCode())
                .itemName(item.getName())
                .version(versionInt)
                .status("approved".equalsIgnoreCase(header.getStatus()) ? "active" : header.getStatus())
                .lines(lineDtos)
                .build();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }
}
