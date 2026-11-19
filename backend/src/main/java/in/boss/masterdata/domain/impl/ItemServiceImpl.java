package in.boss.masterdata.domain.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.masterdata.api.dto.CreateItemRequest;
import in.boss.masterdata.api.dto.ItemDto;
import in.boss.masterdata.api.dto.ItemSpecification;
import in.boss.masterdata.api.dto.PageResponse;
import in.boss.masterdata.api.dto.UpdateItemRequest;
import in.boss.masterdata.domain.DuplicateEntityException;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.ItemService;
import in.boss.masterdata.domain.LifecycleException;
import in.boss.masterdata.domain.ValidationException;
import in.boss.masterdata.events.MasterDataEventPublisher;
import in.boss.masterdata.persistence.HsnCodeEntity;
import in.boss.masterdata.persistence.HsnCodeRepository;
import in.boss.masterdata.persistence.ItemCategoryEntity;
import in.boss.masterdata.persistence.ItemCategoryRepository;
import in.boss.masterdata.persistence.ItemEntity;
import in.boss.masterdata.persistence.ItemOperationalDependencies;
import in.boss.masterdata.persistence.ItemRepository;
import in.boss.masterdata.persistence.UnitOfMeasureEntity;
import in.boss.masterdata.persistence.UnitOfMeasureRepository;
import in.boss.masterdata.persistence.BomHeaderRepository;
import in.boss.masterdata.persistence.BomLineRepository;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContextHolder;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class ItemServiceImpl implements ItemService {

    private final ItemRepository itemRepository;
    private final UnitOfMeasureRepository uomRepository;
    private final ItemCategoryRepository categoryRepository;
    private final HsnCodeRepository hsnCodeRepository;
    private final BomHeaderRepository bomHeaderRepository;
    private final BomLineRepository bomLineRepository;
    private final ItemOperationalDependencies operationalDependencies;
    private final AuditService auditService;
    private final MasterDataEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public ItemServiceImpl(ItemRepository itemRepository,
                           UnitOfMeasureRepository uomRepository,
                           ItemCategoryRepository categoryRepository,
                           HsnCodeRepository hsnCodeRepository,
                           BomHeaderRepository bomHeaderRepository,
                           BomLineRepository bomLineRepository,
                           ItemOperationalDependencies operationalDependencies,
                           AuditService auditService,
                           MasterDataEventPublisher eventPublisher,
                           ObjectMapper objectMapper) {
        this.itemRepository = itemRepository;
        this.uomRepository = uomRepository;
        this.categoryRepository = categoryRepository;
        this.hsnCodeRepository = hsnCodeRepository;
        this.bomHeaderRepository = bomHeaderRepository;
        this.bomLineRepository = bomLineRepository;
        this.operationalDependencies = operationalDependencies;
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
    public ItemDto createItem(CreateItemRequest request) {
        UUID tenantId = getTenantId();

        if (itemRepository.existsByTenantIdAndCode(tenantId, request.getCode().trim())) {
            throw new DuplicateEntityException("Item code already exists for this tenant: " + request.getCode());
        }

        UnitOfMeasureEntity uom = uomRepository.findByIdAndTenantId(request.getUomId(), tenantId)
                .orElseThrow(() -> new ValidationException("Invalid UOM ID: " + request.getUomId()));

        ItemCategoryEntity category = null;
        if (request.getCategoryId() != null) {
            category = categoryRepository.findByIdAndTenantId(request.getCategoryId(), tenantId)
                    .orElseThrow(() -> new ValidationException("Invalid category ID: " + request.getCategoryId()));
        }

        UUID hsnCodeId = null;
        java.math.BigDecimal gstRate = null;
        if (request.getHsnCode() != null && !request.getHsnCode().isBlank()) {
            var hsnOpt = hsnCodeRepository.findByCode(request.getHsnCode().trim());
            if (hsnOpt.isPresent()) {
                HsnCodeEntity hsn = hsnOpt.get();
                hsnCodeId = hsn.getId();
                gstRate = hsn.getGstRate();
            }
        }

        String dbItemType = toDbItemType(request.getItemType());
        List<ItemSpecification> specifications = validateProductConfiguration(
                tenantId, dbItemType, request.getProductKind(), request.getSpecifications());
        Instant now = Instant.now();

        ItemEntity entity = ItemEntity.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .code(request.getCode().trim())
                .name(request.getName().trim())
                .description(request.getDescription())
                .itemType(dbItemType)
                .productKind(request.getProductKind())
                .specifications(specifications)
                .categoryId(category != null ? category.getId() : null)
                .categoryLabelSnapshot(category != null ? category.getName() : null)
                .baseUomId(uom.getId())
                .hsnCodeId(hsnCodeId)
                .gstRate(gstRate)
                .stdCost(request.getStandardCost())
                .reorderQty(request.getReorderPoint())
                .isActive(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        ItemEntity saved = itemRepository.save(entity);

        ItemDto dto = toDto(saved, uom.getSymbol(), category != null ? category.getName() : request.getCategoryLabel(), request.getHsnCode());

        auditService.record("ITEM", saved.getId(), "CREATE", null, toJson(dto));
        eventPublisher.publishEvent("ITEM", saved.getId(), "ITEM_CREATED", dto);

        return dto;
    }

    @Override
    @Transactional(readOnly = true)
    public ItemDto getItem(UUID id) {
        UUID tenantId = getTenantId();
        ItemEntity entity = itemRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Item not found: " + id));

        return loadFullDto(entity, tenantId);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ItemDto updateItem(UUID id, UpdateItemRequest request) {
        UUID tenantId = getTenantId();
        ItemEntity entity = itemRepository.findLockedByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Item not found: " + id));

        if (request.isProductConfigurationProvided() || request.getProductKind() != null || request.getSpecifications() != null) {
            throw new LifecycleException("Product kind and specifications cannot be changed after creation");
        }
        ItemDto oldDto = loadFullDto(entity, tenantId);

        Optional<HsnCodeEntity> requestedHsn = Optional.empty();
        if (request.getHsnCode() != null && !request.getHsnCode().isBlank()) {
            requestedHsn = hsnCodeRepository.findByCode(request.getHsnCode().trim());
        }
        boolean identityChanged = (request.getName() != null && !request.getName().isBlank()
                && !Objects.equals(entity.getName(), request.getName().trim()))
                || (request.getDescription() != null && !Objects.equals(entity.getDescription(), request.getDescription()))
                || (request.getCategoryId() != null && !Objects.equals(entity.getCategoryId(), request.getCategoryId()))
                || (requestedHsn.isPresent() && !Objects.equals(entity.getHsnCodeId(), requestedHsn.get().getId()));
        if (identityChanged) {
            List<String> references = operationalDependencies.findReferences(tenantId, id);
            if (!references.isEmpty()) {
                throw new LifecycleException("Cannot change item identity: referenced by " + String.join(", ", references));
            }
        }

        if (request.getStatus() != null) {
            if (!"active".equalsIgnoreCase(request.getStatus())
                    && !"inactive".equalsIgnoreCase(request.getStatus())) {
                throw new ValidationException("Invalid item status: " + request.getStatus());
            }
            boolean newActive = "active".equalsIgnoreCase(request.getStatus());
            if (!newActive && entity.isActive()) {
                checkNoActiveBomReference(tenantId, id, "deactivate");
                checkNoOperationalDependencies(tenantId, id, "deactivate");
            }
            entity.setActive(newActive);
        }

        if (request.getName() != null && !request.getName().isBlank()) {
            entity.setName(request.getName().trim());
        }
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription());
        }
        if (request.getCategoryId() != null) {
            ItemCategoryEntity cat = categoryRepository.findByIdAndTenantId(request.getCategoryId(), tenantId)
                    .orElseThrow(() -> new ValidationException("Invalid category ID: " + request.getCategoryId()));
            if (!Objects.equals(entity.getCategoryId(), cat.getId())) {
                entity.setCategoryId(cat.getId());
                entity.setCategoryLabelSnapshot(cat.getName());
            }
        }
        if (request.getStandardCost() != null) {
            entity.setStdCost(request.getStandardCost());
        }
        if (request.getReorderPoint() != null) {
            entity.setReorderQty(request.getReorderPoint());
        }
        if (request.getHsnCode() != null && !request.getHsnCode().isBlank()) {
            if (requestedHsn.isPresent()) {
                HsnCodeEntity hsn = requestedHsn.get();
                entity.setHsnCodeId(hsn.getId());
                entity.setGstRate(hsn.getGstRate());
            }
        }

        entity.setUpdatedAt(Instant.now());
        ItemEntity saved = itemRepository.save(entity);

        ItemDto newDto = loadFullDto(saved, tenantId);

        auditService.record("ITEM", saved.getId(), "UPDATE", toJson(oldDto), toJson(newDto));
        eventPublisher.publishEvent("ITEM", saved.getId(), "ITEM_UPDATED", newDto);

        return newDto;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteItem(UUID id) {
        UUID tenantId = getTenantId();
        ItemEntity entity = itemRepository.findLockedByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Item not found: " + id));

        checkNoActiveBomReference(tenantId, id, "delete");
        checkNoOperationalDependencies(tenantId, id, "delete");

        ItemDto oldDto = loadFullDto(entity, tenantId);
        entity.setActive(false);
        entity.setUpdatedAt(Instant.now());
        itemRepository.save(entity);

        auditService.record("ITEM", entity.getId(), "DELETE", toJson(oldDto), null);
        eventPublisher.publishEvent("ITEM", entity.getId(), "ITEM_DELETED", Map.of("id", id, "status", "inactive"));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ItemDto> listItems(String itemType, UUID categoryId, String status, String q, int page, int size) {
        UUID tenantId = getTenantId();

        Specification<ItemEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));

            if (itemType != null && !itemType.isBlank()) {
                String dbType = toDbItemType(itemType);
                predicates.add(cb.equal(root.get("itemType"), dbType));
            }
            if (categoryId != null) {
                predicates.add(cb.equal(root.get("categoryId"), categoryId));
            }
            if (status != null && !status.isBlank()) {
                boolean active = "active".equalsIgnoreCase(status);
                predicates.add(cb.equal(root.get("isActive"), active));
            } else {
                // Default: exclude inactive items from lists unless explicitly requested
                predicates.add(cb.isTrue(root.get("isActive")));
            }
            if (q != null && !q.isBlank()) {
                String searchPattern = q.trim().toLowerCase(Locale.ROOT) + "%";
                Predicate codeMatch = cb.like(cb.lower(root.get("code")), searchPattern);
                Predicate nameMatch = cb.like(cb.lower(root.get("name")), searchPattern);
                predicates.add(cb.or(codeMatch, nameMatch));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        PageRequest pageRequest = PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ItemEntity> entityPage = itemRepository.findAll(spec, pageRequest);

        List<ItemDto> dtos = entityPage.getContent().stream()
                .map(e -> loadFullDto(e, tenantId))
                .collect(Collectors.toList());

        return PageResponse.<ItemDto>builder()
                .content(dtos)
                .page(entityPage.getNumber())
                .size(entityPage.getSize())
                .totalElements(entityPage.getTotalElements())
                .totalPages(entityPage.getTotalPages())
                .build();
    }

    private void checkNoActiveBomReference(UUID tenantId, UUID itemId, String operation) {
        boolean isFinishedItemInActiveBom = bomHeaderRepository
                .existsByTenantIdAndFinishedItemIdAndStatus(tenantId, itemId, "approved");
        if (isFinishedItemInActiveBom) {
            throw new LifecycleException("Cannot " + operation + " item: referenced as finished item in active BOM");
        }

        boolean isComponentInActiveBom = bomLineRepository.isComponentInActiveBom(tenantId, itemId);
        if (isComponentInActiveBom) {
            throw new LifecycleException("Cannot " + operation + " item: referenced as component in active BOM");
        }
    }

    private void checkNoOperationalDependencies(UUID tenantId, UUID itemId, String operation) {
        List<String> dependencies = operationalDependencies.findActive(tenantId, itemId);
        if (!dependencies.isEmpty()) {
            throw new LifecycleException("Cannot " + operation + " item: active dependencies: "
                    + String.join(", ", dependencies));
        }
    }

    private ItemDto loadFullDto(ItemEntity entity, UUID tenantId) {
        String uomSymbol = uomRepository.findByIdAndTenantId(entity.getBaseUomId(), tenantId)
                .map(UnitOfMeasureEntity::getSymbol)
                .orElse(null);

        String categoryLabel = entity.getCategoryLabelSnapshot();
        if (categoryLabel == null && entity.getCategoryId() != null) {
            categoryLabel = categoryRepository.findByIdAndTenantId(entity.getCategoryId(), tenantId)
                    .map(ItemCategoryEntity::getName)
                    .orElse(null);
        }

        String hsnCode = null;
        if (entity.getHsnCodeId() != null) {
            hsnCode = hsnCodeRepository.findById(entity.getHsnCodeId())
                    .map(HsnCodeEntity::getCode)
                    .orElse(null);
        }

        return toDto(entity, uomSymbol, categoryLabel, hsnCode);
    }

    private ItemDto toDto(ItemEntity entity, String uomSymbol, String categoryLabel, String hsnCode) {
        return ItemDto.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .description(entity.getDescription())
                .itemType(toApiItemType(entity.getItemType()))
                .productKind(entity.getProductKind())
                .specifications(entity.getSpecifications())
                .categoryId(entity.getCategoryId())
                .categoryLabel(categoryLabel)
                .uomId(entity.getBaseUomId())
                .uomSymbol(uomSymbol)
                .hsnCode(hsnCode)
                .gstRate(entity.getGstRate())
                .standardCost(entity.getStdCost())
                .reorderPoint(entity.getReorderQty())
                .status(entity.isActive() ? "active" : "inactive")
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private List<ItemSpecification> validateProductConfiguration(UUID tenantId, String itemType, String productKind,
                                                                  List<ItemSpecification> input) {
        List<ItemSpecification> specifications = input == null ? List.of() : input;
        if (productKind != null && !List.of("MANUFACTURED", "TRADED").contains(productKind)) {
            throw new ValidationException("Product kind must be MANUFACTURED or TRADED");
        }
        if (!"finished_goods".equals(itemType) && (productKind != null || !specifications.isEmpty())) {
            throw new ValidationException("Product kind and specifications are only supported for finished goods");
        }
        if (specifications.size() > 20) {
            throw new ValidationException("At most 20 specifications are supported");
        }
        var keys = new HashSet<String>();
        List<ItemSpecification> validated = new ArrayList<>();
        for (ItemSpecification spec : specifications) {
            if (spec == null || spec.getKey() == null || !spec.getKey().matches("[a-z][a-z0-9_]{0,49}")
                    || !keys.add(spec.getKey())) {
                throw new ValidationException("Specification keys must be unique lowercase identifiers (letters, digits and underscores)");
            }
            if (spec.getLabel() == null || spec.getLabel().isBlank() || spec.getLabel().trim().length() > 100) {
                throw new ValidationException("Specification label is required and must be at most 100 characters");
            }
            if (spec.getUomId() == null) {
                throw new ValidationException("A unit of measure is required for each specification");
            }
            UnitOfMeasureEntity unit = uomRepository.findByIdAndTenantId(spec.getUomId(), tenantId)
                    .orElseThrow(() -> new ValidationException("Invalid specification UOM ID: " + spec.getUomId()));
            for (BigDecimal value : new BigDecimal[]{spec.getMinValue(), spec.getMaxValue(), spec.getDefaultValue()}) {
                if (value != null && (value.precision() - Math.min(value.scale(), 0) > 18 || Math.max(value.scale(), 0) > 6)) {
                    throw new ValidationException("Specification values support at most 18 digits and 6 decimal places");
                }
            }
            if (spec.getMinValue() != null && spec.getMaxValue() != null
                    && spec.getMinValue().compareTo(spec.getMaxValue()) > 0) {
                throw new ValidationException("Specification minimum cannot exceed maximum");
            }
            if (spec.getDefaultValue() != null && ((spec.getMinValue() != null && spec.getDefaultValue().compareTo(spec.getMinValue()) < 0)
                    || (spec.getMaxValue() != null && spec.getDefaultValue().compareTo(spec.getMaxValue()) > 0))) {
                throw new ValidationException("Specification default must be within its bounds");
            }
            validated.add(ItemSpecification.builder().key(spec.getKey()).label(spec.getLabel().trim())
                    .uomId(unit.getId()).uomSymbol(unit.getSymbol()).minValue(spec.getMinValue())
                    .maxValue(spec.getMaxValue()).defaultValue(spec.getDefaultValue()).build());
        }
        return validated;
    }

    private String toDbItemType(String apiType) {
        if (apiType == null) return "raw_material";
        return switch (apiType.toUpperCase(Locale.ROOT)) {
            case "RAW_MATERIAL" -> "raw_material";
            case "CONSUMABLE" -> "consumable";
            case "FINISHED_GOOD" -> "finished_goods";
            case "SERVICE" -> "service";
            case "SPARE_PART" -> "other";
            case "SEMI_FINISHED" -> "semi_finished";
            default -> apiType.toLowerCase(Locale.ROOT);
        };
    }

    private String toApiItemType(String dbType) {
        if (dbType == null) return "RAW_MATERIAL";
        return switch (dbType.toLowerCase(Locale.ROOT)) {
            case "raw_material" -> "RAW_MATERIAL";
            case "consumable" -> "CONSUMABLE";
            case "finished_goods" -> "FINISHED_GOOD";
            case "service" -> "SERVICE";
            case "other", "tool", "asset" -> "SPARE_PART";
            case "semi_finished" -> "SEMI_FINISHED";
            default -> dbType.toUpperCase(Locale.ROOT);
        };
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }
}
