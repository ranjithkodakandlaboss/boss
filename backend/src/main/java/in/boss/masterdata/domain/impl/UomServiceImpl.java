package in.boss.masterdata.domain.impl;

import in.boss.masterdata.api.dto.CreateUomRequest;
import in.boss.masterdata.api.dto.UomDto;
import in.boss.masterdata.domain.DuplicateEntityException;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.UomService;
import in.boss.masterdata.events.MasterDataEventPublisher;
import in.boss.masterdata.persistence.UnitOfMeasureEntity;
import in.boss.masterdata.persistence.UnitOfMeasureRepository;
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
public class UomServiceImpl implements UomService {

    private final UnitOfMeasureRepository uomRepository;
    private final AuditService auditService;
    private final MasterDataEventPublisher eventPublisher;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public UomServiceImpl(UnitOfMeasureRepository uomRepository,
                          AuditService auditService,
                          MasterDataEventPublisher eventPublisher,
                          com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
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
    public List<UomDto> listUoms(String uomType) {
        UUID tenantId = getTenantId();
        List<UnitOfMeasureEntity> entities;
        if (uomType != null && !uomType.isBlank()) {
            entities = uomRepository.findByTenantIdAndUomType(tenantId, uomType.toLowerCase());
        } else {
            entities = uomRepository.findByTenantId(tenantId);
        }
        return entities.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public UomDto getUom(UUID id) {
        UUID tenantId = getTenantId();
        UnitOfMeasureEntity entity = uomRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("UOM not found: " + id));
        return toDto(entity);
    }

    @Override
    public UomDto createUom(CreateUomRequest request) {
        UUID tenantId = getTenantId();
        String symbol = request.getSymbol().trim();

        if (uomRepository.existsByTenantIdAndSymbol(tenantId, symbol)) {
            throw new DuplicateEntityException("UOM symbol already exists for this tenant: " + symbol);
        }

        String uomType = request.getUomType() != null && !request.getUomType().isBlank()
                ? request.getUomType().toLowerCase()
                : "quantity";
        boolean isBase = Boolean.TRUE.equals(request.getIsBase());

        UnitOfMeasureEntity entity = UnitOfMeasureEntity.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .name(request.getName().trim())
                .symbol(symbol)
                .uomType(uomType)
                .isBase(isBase)
                .createdAt(Instant.now())
                .build();

        UnitOfMeasureEntity saved = uomRepository.save(entity);
        UomDto dto = toDto(saved);

        auditService.record("UOM", saved.getId(), "CREATE", null, toJson(dto));
        eventPublisher.publishEvent("UOM", saved.getId(), "UOM_CREATED", dto);

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

    private UomDto toDto(UnitOfMeasureEntity entity) {
        return UomDto.builder()
                .id(entity.getId())
                .name(entity.getName())
                .symbol(entity.getSymbol())
                .uomType(entity.getUomType())
                .isBase(entity.isBase())
                .build();
    }
}
