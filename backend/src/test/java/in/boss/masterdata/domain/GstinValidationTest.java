package in.boss.masterdata.domain;

import in.boss.masterdata.api.dto.CreateCustomerRequest;
import in.boss.masterdata.api.dto.CreateSupplierRequest;
import in.boss.masterdata.domain.impl.CustomerServiceImpl;
import in.boss.masterdata.domain.impl.SupplierServiceImpl;
import in.boss.masterdata.persistence.CustomerContactRepository;
import in.boss.masterdata.persistence.CustomerRepository;
import in.boss.masterdata.persistence.SupplierContactRepository;
import in.boss.masterdata.persistence.SupplierRepository;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class GstinValidationTest {

    @Mock CustomerRepository customerRepo;
    @Mock CustomerContactRepository customerContactRepo;
    @Mock SupplierRepository supplierRepo;
    @Mock SupplierContactRepository supplierContactRepo;
    @Mock AuditService auditService;

    private CustomerService customerService;
    private SupplierService supplierService;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        customerService = new CustomerServiceImpl(customerRepo, customerContactRepo, auditService, null, null);
        supplierService = new SupplierServiceImpl(supplierRepo, supplierContactRepo, auditService, null, null);
        BossContextHolder.set(new BossContext(tenantId, UUID.randomUUID(), Set.of("OWNER"), null, null));
    }

    @AfterEach
    void tearDown() {
        BossContextHolder.clear();
    }

    @Test
    void validGstin_matchesPattern() {
        String validGstin = "27AABCU9603R1ZM";
        assertThat(CustomerServiceImpl.GSTIN_PATTERN.matcher(validGstin).matches()).isTrue();
        assertThat(SupplierServiceImpl.GSTIN_PATTERN.matcher(validGstin).matches()).isTrue();
    }

    @Test
    void invalidGstin_wrongLength_throwsValidationException() {
        CreateCustomerRequest req = CreateCustomerRequest.builder()
                .code("CUST01")
                .name("Test Customer")
                .gstin("27AABCU9603R1Z") // 14 chars instead of 15
                .build();

        assertThatThrownBy(() -> customerService.createCustomer(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid GSTIN format");
    }

    @Test
    void invalidGstin_invalidStateCode_throwsValidationException() {
        CreateCustomerRequest req = CreateCustomerRequest.builder()
                .code("CUST01")
                .name("Test Customer")
                .gstin("XXAABCU9603R1ZM") // non-numeric state code
                .build();

        assertThatThrownBy(() -> customerService.createCustomer(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid GSTIN format");
    }

    @Test
    void invalidGstin_invalidSupplier_throwsValidationException() {
        CreateSupplierRequest req = CreateSupplierRequest.builder()
                .code("SUP01")
                .name("Test Supplier")
                .gstin("INVALID-GSTIN")
                .build();

        assertThatThrownBy(() -> supplierService.createSupplier(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid GSTIN format");
    }

    @Test
    void validPan_matchesPattern() {
        String validPan = "AABCU9603R";
        assertThat(CustomerServiceImpl.PAN_PATTERN.matcher(validPan).matches()).isTrue();
    }

    @Test
    void invalidPan_throwsValidationException() {
        CreateCustomerRequest req = CreateCustomerRequest.builder()
                .code("CUST01")
                .name("Test Customer")
                .pan("12345ABCDE")
                .build();

        assertThatThrownBy(() -> customerService.createCustomer(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid PAN format");
    }
}
