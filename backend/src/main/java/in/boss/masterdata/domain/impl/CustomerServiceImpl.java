package in.boss.masterdata.domain.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.masterdata.api.dto.ContactDto;
import in.boss.masterdata.api.dto.CreateCustomerRequest;
import in.boss.masterdata.api.dto.CustomerDto;
import in.boss.masterdata.api.dto.PageResponse;
import in.boss.masterdata.api.dto.UpdateCustomerRequest;
import in.boss.masterdata.domain.CustomerService;
import in.boss.masterdata.domain.DuplicateEntityException;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.ValidationException;
import in.boss.masterdata.events.MasterDataEventPublisher;
import in.boss.masterdata.events.CustomerDeactivationRequested;
import in.boss.masterdata.persistence.CustomerContactEntity;
import in.boss.masterdata.persistence.CustomerContactRepository;
import in.boss.masterdata.persistence.CustomerEntity;
import in.boss.masterdata.persistence.CustomerRepository;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContextHolder;
import jakarta.persistence.criteria.Predicate;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
public class CustomerServiceImpl implements CustomerService {

    public static final Pattern GSTIN_PATTERN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    public static final Pattern PAN_PATTERN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");

    private final CustomerRepository customerRepository;
    private final CustomerContactRepository contactRepository;
    private final AuditService auditService;
    private final MasterDataEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEvents;

    public CustomerServiceImpl(CustomerRepository customerRepository,
                               CustomerContactRepository contactRepository,
                               AuditService auditService,
                               MasterDataEventPublisher eventPublisher,
                               ObjectMapper objectMapper,
                               ApplicationEventPublisher applicationEvents) {
        this.customerRepository = customerRepository;
        this.contactRepository = contactRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.applicationEvents = applicationEvents;
    }

    private UUID getTenantId() {
        var ctx = BossContextHolder.get();
        if (ctx == null || ctx.tenantId() == null) {
            throw new IllegalStateException("Tenant context is required");
        }
        return ctx.tenantId();
    }

    @Override
    public CustomerDto createCustomer(CreateCustomerRequest request) {
        UUID tenantId = getTenantId();
        String code = request.getCode().trim();
        String gstin = normalizeGstin(request.getGstin());

        if (customerRepository.existsByTenantIdAndCode(tenantId, code)) {
            throw new DuplicateEntityException("Customer code already exists: " + code);
        }

        validateGstinAndPan(gstin, request.getPan());
        ensureGstinAvailable(tenantId, gstin, null);
        validateRating(request.getRating());

        Instant now = Instant.now();
        UUID customerId = UUID.randomUUID();

        CustomerEntity entity = CustomerEntity.builder()
                .id(customerId)
                .tenantId(tenantId)
                .code(code)
                .name(request.getName().trim())
                .legalName(request.getLegalName())
                .gstin(gstin)
                .pan(cleanStr(request.getPan()))
                .customerType("business")
                .billingAddress(toJson(request.getAddress()))
                .shippingAddress(toJson(request.getAddress()))
                .creditLimit(request.getCreditLimit())
                .creditDays(request.getCreditDays() != null ? request.getCreditDays() : 30)
                .rating(request.getRating())
                .isActive(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        CustomerEntity saved = saveCustomer(entity, gstin);

        List<CustomerContactEntity> savedContacts = new ArrayList<>();
        if (request.getContacts() != null) {
            for (ContactDto c : request.getContacts()) {
                CustomerContactEntity contact = CustomerContactEntity.builder()
                        .id(UUID.randomUUID())
                        .tenantId(tenantId)
                        .customerId(customerId)
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

        CustomerDto dto = toDto(saved, savedContacts);

        auditService.record("CUSTOMER", saved.getId(), "CREATE", null, toJson(dto));
        eventPublisher.publishEvent("CUSTOMER", saved.getId(), "CUSTOMER_CREATED", dto);

        return dto;
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerDto getCustomer(UUID id) {
        UUID tenantId = getTenantId();
        CustomerEntity entity = customerRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Customer not found: " + id));

        List<CustomerContactEntity> contacts = contactRepository.findByTenantIdAndCustomerId(tenantId, id);
        return toDto(entity, contacts);
    }

    @Override
    public CustomerDto updateCustomer(UUID id, UpdateCustomerRequest request) {
        UUID tenantId = getTenantId();
        CustomerEntity entity = customerRepository.findForUpdateByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Customer not found: " + id));
        validateCustomerUpdate(entity, request);
        if (entity.isActive() && "inactive".equalsIgnoreCase(request.getStatus())) {
            applicationEvents.publishEvent(new CustomerDeactivationRequested(tenantId, id));
        }

        List<CustomerContactEntity> currentContacts = contactRepository.findByTenantIdAndCustomerId(tenantId, id);
        CustomerDto oldDto = toDto(entity, currentContacts);
        if (request.changesRating()) {
            validateRating(request.getRating());
            entity.setRating(request.getRating());
        }

        String updatedGstin = request.getGstin() != null
                ? normalizeGstin(request.getGstin())
                : entity.getGstin();
        if (request.getGstin() != null || request.getPan() != null) {
            validateGstinAndPan(
                    updatedGstin,
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
            ensureGstinAvailable(tenantId, updatedGstin, id);
            entity.setGstin(updatedGstin);
        }
        if (request.getPan() != null) {
            entity.setPan(cleanStr(request.getPan()));
        }
        if (request.getAddress() != null) {
            entity.setBillingAddress(toJson(request.getAddress()));
            entity.setShippingAddress(toJson(request.getAddress()));
        }
        if (request.getCreditLimit() != null) {
            entity.setCreditLimit(request.getCreditLimit());
        }
        if (request.getCreditDays() != null) {
            entity.setCreditDays(request.getCreditDays());
        }
        if (request.getStatus() != null) {
            entity.setActive("active".equalsIgnoreCase(request.getStatus()));
        }

        entity.setUpdatedAt(Instant.now());
        CustomerEntity saved = saveCustomer(entity, entity.getGstin());

        List<CustomerContactEntity> updatedContacts = currentContacts;
        if (request.getContacts() != null) {
            contactRepository.deleteByTenantIdAndCustomerId(tenantId, id);
            updatedContacts = new ArrayList<>();
            for (ContactDto c : request.getContacts()) {
                CustomerContactEntity contact = CustomerContactEntity.builder()
                        .id(UUID.randomUUID())
                        .tenantId(tenantId)
                        .customerId(id)
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

        CustomerDto newDto = toDto(saved, updatedContacts);

        auditService.record("CUSTOMER", saved.getId(), "UPDATE", toJson(oldDto), toJson(newDto));
        eventPublisher.publishEvent("CUSTOMER", saved.getId(), "CUSTOMER_UPDATED", newDto);

        return newDto;
    }

    private static void validateCustomerUpdate(CustomerEntity entity, UpdateCustomerRequest request) {
        if (request.getStatus() != null
                && !"active".equalsIgnoreCase(request.getStatus())
                && !"inactive".equalsIgnoreCase(request.getStatus())) {
            throw new ValidationException("Customer status must be active or inactive");
        }
        if (!entity.isActive()
                && (!"active".equalsIgnoreCase(request.getStatus()) || request.changesProfile())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Inactive customers cannot be edited. Reactivate the customer first.");
        }
    }

    @Override
    public void deleteCustomer(UUID id) {
        UUID tenantId = getTenantId();
        CustomerEntity entity = customerRepository.findForUpdateByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Customer not found: " + id));
        if (!entity.isActive()) {
            return;
        }
        applicationEvents.publishEvent(new CustomerDeactivationRequested(tenantId, id));

        List<CustomerContactEntity> contacts = contactRepository.findByTenantIdAndCustomerId(tenantId, id);
        CustomerDto oldDto = toDto(entity, contacts);

        entity.setActive(false);
        entity.setUpdatedAt(Instant.now());
        customerRepository.save(entity);

        auditService.record("CUSTOMER", entity.getId(), "DELETE", toJson(oldDto), null);
        eventPublisher.publishEvent("CUSTOMER", entity.getId(), "CUSTOMER_DELETED", Map.of("id", id, "status", "inactive"));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CustomerDto> listCustomers(String status, String q, int page, int size) {
        UUID tenantId = getTenantId();

        Specification<CustomerEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));

            if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
                boolean active = "active".equalsIgnoreCase(status);
                predicates.add(cb.equal(root.get("isActive"), active));
            } else if (status == null || status.isBlank()) {
                predicates.add(cb.isTrue(root.get("isActive")));
            }
            // status=all: no isActive filter — return both active and inactive
            if (q != null && !q.isBlank()) {
                String searchPattern = q.trim().toLowerCase(Locale.ROOT) + "%";
                Predicate codeMatch = cb.like(cb.lower(root.get("code")), searchPattern);
                Predicate nameMatch = cb.like(cb.lower(root.get("name")), searchPattern);
                predicates.add(cb.or(codeMatch, nameMatch));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        PageRequest pageRequest = PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<CustomerEntity> entityPage = customerRepository.findAll(spec, pageRequest);

        List<CustomerDto> dtos = entityPage.getContent().stream()
                .map(e -> {
                    List<CustomerContactEntity> contacts = contactRepository.findByTenantIdAndCustomerId(tenantId, e.getId());
                    return toDto(e, contacts);
                })
                .collect(Collectors.toList());

        return PageResponse.<CustomerDto>builder()
                .content(dtos)
                .page(entityPage.getNumber())
                .size(entityPage.getSize())
                .totalElements(entityPage.getTotalElements())
                .totalPages(entityPage.getTotalPages())
                .build();
    }

    private static void validateRating(Integer rating) {
        if (rating != null && (rating < 0 || rating > 5)) {
            throw new ValidationException("Customer rating must be between 0 and 5");
        }
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

    private String normalizeGstin(String gstin) {
        String cleaned = cleanStr(gstin);
        return cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
    }

    private void ensureGstinAvailable(UUID tenantId, String gstin, UUID customerId) {
        if (gstin == null) return;
        boolean exists = customerId == null
                ? customerRepository.existsByTenantIdAndGstinIgnoreCase(tenantId, gstin)
                : customerRepository.existsByTenantIdAndGstinIgnoreCaseAndIdNot(tenantId, gstin, customerId);
        if (exists) {
            throw new DuplicateEntityException("GSTIN already exists for a customer in this tenant: " + gstin);
        }
    }

    private CustomerEntity saveCustomer(CustomerEntity entity, String gstin) {
        try {
            return customerRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            if (isGstinUniqueConstraintViolation(exception)) {
                throw new DuplicateEntityException(
                        "GSTIN already exists for a customer in this tenant: " + gstin);
            }
            throw exception;
        }
    }

    private boolean isGstinUniqueConstraintViolation(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "uq_customers_tenant_gstin".equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private String cleanStr(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private CustomerDto toDto(CustomerEntity entity, List<CustomerContactEntity> contacts) {
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

        return CustomerDto.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .legalName(entity.getLegalName())
                .gstin(entity.getGstin())
                .pan(entity.getPan())
                .customerTypeId(null)
                .customerTypeLabel(entity.getCustomerType())
                .address(addressMap)
                .creditLimit(entity.getCreditLimit())
                .creditDays(entity.getCreditDays())
                .rating(entity.getRating())
                .status(entity.isActive() ? "active" : "inactive")
                .contacts(contactDtos)
                .createdAt(entity.getCreatedAt())
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
