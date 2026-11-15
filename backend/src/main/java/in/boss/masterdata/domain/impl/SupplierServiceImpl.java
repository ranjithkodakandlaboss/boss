package in.boss.masterdata.domain.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.masterdata.api.dto.ContactDto;
import in.boss.masterdata.api.dto.CreateSupplierRequest;
import in.boss.masterdata.api.dto.PageResponse;
import in.boss.masterdata.api.dto.SupplierDto;
import in.boss.masterdata.api.dto.UpdateSupplierRequest;
import in.boss.masterdata.domain.DuplicateEntityException;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.SupplierService;
import in.boss.masterdata.domain.ValidationException;
import in.boss.masterdata.events.MasterDataEventPublisher;
import in.boss.masterdata.persistence.SupplierContactEntity;
import in.boss.masterdata.persistence.SupplierContactRepository;
import in.boss.masterdata.persistence.SupplierEntity;
import in.boss.masterdata.persistence.SupplierRepository;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContextHolder;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Transactional
public class SupplierServiceImpl implements SupplierService {

    public static final Pattern GSTIN_PATTERN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    public static final Pattern PAN_PATTERN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");

    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository contactRepository;
    private final AuditService auditService;
    private final MasterDataEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public SupplierServiceImpl(SupplierRepository supplierRepository,
                               SupplierContactRepository contactRepository,
                               AuditService auditService,
                               MasterDataEventPublisher eventPublisher,
                               ObjectMapper objectMapper) {
        this.supplierRepository = supplierRepository;
        this.contactRepository = contactRepository;
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
    public SupplierDto createSupplier(CreateSupplierRequest request) {
        UUID tenantId = getTenantId();
        String code = request.getCode().trim();

        if (supplierRepository.existsByTenantIdAndCode(tenantId, code)) {
            throw new DuplicateEntityException("Supplier code already exists: " + code);
        }

        validateGstinAndPan(request.getGstin(), request.getPan());

        Instant now = Instant.now();
        UUID supplierId = UUID.randomUUID();

        SupplierEntity entity = SupplierEntity.builder()
                .id(supplierId)
                .tenantId(tenantId)
                .code(code)
                .name(request.getName().trim())
                .legalName(request.getLegalName())
                .gstin(cleanStr(request.getGstin()))
                .pan(cleanStr(request.getPan()))
                .supplierType("manufacturer")
                .billingAddress(toJson(request.getAddress()))
                .paymentTerms(request.getPaymentTerms() != null ? request.getPaymentTerms() : 30)
                .creditLimit(request.getCreditLimit())
                .isActive(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        SupplierEntity saved = supplierRepository.save(entity);

        List<SupplierContactEntity> savedContacts = new ArrayList<>();
        if (request.getContacts() != null) {
            for (ContactDto c : request.getContacts()) {
                SupplierContactEntity contact = SupplierContactEntity.builder()
                        .id(UUID.randomUUID())
                        .tenantId(tenantId)
                        .supplierId(supplierId)
                        .name(c.getName())
                        .role(c.getRole())
                        .email(c.getEmail())
                        .phone(c.getPhone())
                        .isPrimary(Boolean.TRUE.equals(c.getIsPrimary()))
                        .createdAt(now)
                        .build();
                savedContacts.add(contactRepository.save(contact));
            }
        }

        SupplierDto dto = toDto(saved, savedContacts);

        auditService.record("SUPPLIER", saved.getId(), "CREATE", null, toJson(dto));
        eventPublisher.publishEvent("SUPPLIER", saved.getId(), "SUPPLIER_CREATED", dto);

        return dto;
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierDto getSupplier(UUID id) {
        UUID tenantId = getTenantId();
        SupplierEntity entity = supplierRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Supplier not found: " + id));

        List<SupplierContactEntity> contacts = contactRepository.findByTenantIdAndSupplierId(tenantId, id);
        return toDto(entity, contacts);
    }

    @Override
    public SupplierDto updateSupplier(UUID id, UpdateSupplierRequest request) {
        UUID tenantId = getTenantId();
        SupplierEntity entity = supplierRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Supplier not found: " + id));

        List<SupplierContactEntity> currentContacts = contactRepository.findByTenantIdAndSupplierId(tenantId, id);
        SupplierDto oldDto = toDto(entity, currentContacts);

        if (request.getGstin() != null || request.getPan() != null) {
            validateGstinAndPan(
                    request.getGstin() != null ? request.getGstin() : entity.getGstin(),
                    request.getPan() != null ? request.getPan() : entity.getPan()
            );
        }

        if (request.getName() != null && !request.getName().isBlank()) {
            entity.setName(request.getName().trim());
        }
        if (request.getLegalName() != null) {
            entity.setLegalName(request.getLegalName().trim());
        }
        if (request.getGstin() != null) {
            entity.setGstin(cleanStr(request.getGstin()));
        }
        if (request.getPan() != null) {
            entity.setPan(cleanStr(request.getPan()));
        }
        if (request.getAddress() != null) {
            entity.setBillingAddress(toJson(request.getAddress()));
        }
        if (request.getPaymentTerms() != null) {
            entity.setPaymentTerms(request.getPaymentTerms());
        }
        if (request.getCreditLimit() != null) {
            entity.setCreditLimit(request.getCreditLimit());
        }
        if (request.getStatus() != null) {
            entity.setActive("active".equalsIgnoreCase(request.getStatus()));
        }

        entity.setUpdatedAt(Instant.now());
        SupplierEntity saved = supplierRepository.save(entity);

        List<SupplierContactEntity> updatedContacts = currentContacts;
        if (request.getContacts() != null) {
            contactRepository.deleteByTenantIdAndSupplierId(tenantId, id);
            updatedContacts = new ArrayList<>();
            for (ContactDto c : request.getContacts()) {
                SupplierContactEntity contact = SupplierContactEntity.builder()
                        .id(UUID.randomUUID())
                        .tenantId(tenantId)
                        .supplierId(id)
                        .name(c.getName())
                        .role(c.getRole())
                        .email(c.getEmail())
                        .phone(c.getPhone())
                        .isPrimary(Boolean.TRUE.equals(c.getIsPrimary()))
                        .createdAt(Instant.now())
                        .build();
                updatedContacts.add(contactRepository.save(contact));
            }
        }

        SupplierDto newDto = toDto(saved, updatedContacts);

        auditService.record("SUPPLIER", saved.getId(), "UPDATE", toJson(oldDto), toJson(newDto));
        eventPublisher.publishEvent("SUPPLIER", saved.getId(), "SUPPLIER_UPDATED", newDto);

        return newDto;
    }

    @Override
    public void deleteSupplier(UUID id) {
        UUID tenantId = getTenantId();
        SupplierEntity entity = supplierRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Supplier not found: " + id));

        List<SupplierContactEntity> contacts = contactRepository.findByTenantIdAndSupplierId(tenantId, id);
        SupplierDto oldDto = toDto(entity, contacts);

        entity.setActive(false);
        entity.setUpdatedAt(Instant.now());
        supplierRepository.save(entity);

        auditService.record("SUPPLIER", entity.getId(), "DELETE", toJson(oldDto), null);
        eventPublisher.publishEvent("SUPPLIER", entity.getId(), "SUPPLIER_DELETED", Map.of("id", id, "status", "inactive"));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SupplierDto> listSuppliers(String status, String q, int page, int size) {
        UUID tenantId = getTenantId();

        Specification<SupplierEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));

            if (status != null && !status.isBlank()) {
                boolean active = "active".equalsIgnoreCase(status);
                predicates.add(cb.equal(root.get("isActive"), active));
            } else {
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
        Page<SupplierEntity> entityPage = supplierRepository.findAll(spec, pageRequest);

        List<SupplierDto> dtos = entityPage.getContent().stream()
                .map(e -> {
                    List<SupplierContactEntity> contacts = contactRepository.findByTenantIdAndSupplierId(tenantId, e.getId());
                    return toDto(e, contacts);
                })
                .collect(Collectors.toList());

        return PageResponse.<SupplierDto>builder()
                .content(dtos)
                .page(entityPage.getNumber())
                .size(entityPage.getSize())
                .totalElements(entityPage.getTotalElements())
                .totalPages(entityPage.getTotalPages())
                .build();
    }

    private void validateGstinAndPan(String gstin, String pan) {
        if (gstin != null && !gstin.isBlank()) {
            String trimmed = gstin.trim();
            if (!GSTIN_PATTERN.matcher(trimmed).matches()) {
                throw new ValidationException("Invalid GSTIN format: " + gstin);
            }
        }
        if (pan != null && !pan.isBlank()) {
            String trimmed = pan.trim();
            if (!PAN_PATTERN.matcher(trimmed).matches()) {
                throw new ValidationException("Invalid PAN format: " + pan);
            }
        }
    }

    private String cleanStr(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private SupplierDto toDto(SupplierEntity entity, List<SupplierContactEntity> contacts) {
        Map<String, Object> addressMap = fromJson(entity.getBillingAddress());

        List<ContactDto> contactDtos = contacts.stream()
                .map(c -> ContactDto.builder()
                        .id(c.getId())
                        .name(c.getName())
                        .role(c.getRole())
                        .email(c.getEmail())
                        .phone(c.getPhone())
                        .isPrimary(c.isPrimary())
                        .build())
                .collect(Collectors.toList());

        return SupplierDto.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .legalName(entity.getLegalName())
                .gstin(entity.getGstin())
                .pan(entity.getPan())
                .supplierCategoryId(null)
                .supplierCategoryLabel(entity.getSupplierType())
                .address(addressMap)
                .status(entity.isActive() ? "active" : "inactive")
                .performanceScore(entity.getPerformanceScore())
                .paymentTerms(entity.getPaymentTerms())
                .creditLimit(entity.getCreditLimit())
                .contacts(contactDtos)
                .build();
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
