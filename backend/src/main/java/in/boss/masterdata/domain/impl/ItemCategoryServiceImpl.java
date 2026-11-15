package in.boss.masterdata.domain.impl;

import in.boss.masterdata.api.dto.CreateItemCategoryRequest;
import in.boss.masterdata.api.dto.ItemCategoryDto;
import in.boss.masterdata.domain.DuplicateEntityException;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.ItemCategoryService;
import in.boss.masterdata.events.MasterDataEventPublisher;
import in.boss.masterdata.persistence.ItemCategoryEntity;
import in.boss.masterdata.persistence.ItemCategoryRepository;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class ItemCategoryServiceImpl implements ItemCategoryService {

    private final ItemCategoryRepository categoryRepository;
    private final AuditService auditService;
    private final MasterDataEventPublisher eventPublisher;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public ItemCategoryServiceImpl(ItemCategoryRepository categoryRepository,
                                   AuditService auditService,
                                   MasterDataEventPublisher eventPublisher,
                                   com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.categoryRepository = categoryRepository;
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
    public List<ItemCategoryDto> listCategories() {
        UUID tenantId = getTenantId();
        return categoryRepository.findByTenantIdAndIsActiveTrue(tenantId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ItemCategoryDto getCategory(UUID id) {
        UUID tenantId = getTenantId();
        ItemCategoryEntity entity = categoryRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Category not found: " + id));
        return toDto(entity);
    }

    @Override
    public ItemCategoryDto createCategory(CreateItemCategoryRequest request) {
        UUID tenantId = getTenantId();
        String code = request.getCode().trim();

        if (categoryRepository.existsByTenantIdAndCode(tenantId, code)) {
            throw new DuplicateEntityException("Category code already exists for this tenant: " + code);
        }

        ItemCategoryEntity entity = ItemCategoryEntity.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .code(code)
                .name(request.getName().trim())
                .parentId(request.getParentId())
                .description(request.getDescription())
                .isActive(true)
                .createdAt(Instant.now())
                .build();

        ItemCategoryEntity saved = categoryRepository.save(entity);
        ItemCategoryDto dto = toDto(saved);

        auditService.record("ITEM_CATEGORY", saved.getId(), "CREATE", null, toJson(dto));
        eventPublisher.publishEvent("ITEM_CATEGORY", saved.getId(), "CATEGORY_CREATED", dto);

        return dto;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private ItemCategoryDto toDto(ItemCategoryEntity entity) {
        return ItemCategoryDto.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .parentId(entity.getParentId())
                .description(entity.getDescription())
                .isActive(entity.isActive())
                .build();
    }
}
