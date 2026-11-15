package in.boss.masterdata;

import in.boss.masterdata.api.dto.ContactDto;
import in.boss.masterdata.api.dto.CreateCustomerRequest;
import in.boss.masterdata.api.dto.CustomerDto;
import in.boss.masterdata.api.dto.PageResponse;
import in.boss.masterdata.api.dto.UpdateCustomerRequest;
import in.boss.masterdata.domain.CustomerService;
import in.boss.masterdata.domain.DuplicateEntityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerCrudTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private CustomerService customerService;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = provisionTenant("cust-test");
        setTenantContext(tenantId, "OWNER");
    }

    @Test
    void createAndGetCustomer_withContacts_success() {
        String code = "CUST-" + UUID.randomUUID().toString().substring(0, 6);
        CreateCustomerRequest request = CreateCustomerRequest.builder()
                .code(code)
                .name("Acme Auto Parts Ltd")
                .legalName("Acme Automotive Components India Private Limited")
                .gstin("27AABCU9603R1ZM")
                .pan("AABCU9603R")
                .address(Map.of("city", "Pune", "state", "Maharashtra", "pincode", "411018"))
                .creditLimit(new BigDecimal("500000.00"))
                .creditDays(45)
                .contacts(List.of(
                        ContactDto.builder()
                                .name("Rajesh Sharma")
                                .role("Procurement Head")
                                .phone("+919876543210")
                                .email("rajesh@acme.example.com")
                                .isPrimary(true)
                                .build()
                ))
                .build();

        CustomerDto created = customerService.createCustomer(request);
        assertThat(created.getId()).isNotNull();
        assertThat(created.getCode()).isEqualTo(code);
        assertThat(created.getGstin()).isEqualTo("27AABCU9603R1ZM");
        assertThat(created.getContacts()).hasSize(1);
        assertThat(created.getContacts().get(0).getName()).isEqualTo("Rajesh Sharma");

        CustomerDto fetched = customerService.getCustomer(created.getId());
        assertThat(fetched.getId()).isEqualTo(created.getId());
        assertThat(fetched.getContacts()).hasSize(1);

        // Verify audit event
        int auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND entity_id = ?",
                Integer.class, tenantId, created.getId());
        assertThat(auditCount).isGreaterThan(0);
    }

    @Test
    void duplicateCustomerCode_throwsDuplicateEntityException() {
        String code = "DUP-CUST";
        customerService.createCustomer(CreateCustomerRequest.builder()
                .code(code)
                .name("Customer One")
                .build());

        assertThatThrownBy(() -> customerService.createCustomer(CreateCustomerRequest.builder()
                .code(code)
                .name("Customer Two")
                .build()))
                .isInstanceOf(DuplicateEntityException.class)
                .hasMessageContaining("Customer code already exists");
    }

    @Test
    void updateAndSoftDeleteCustomer() {
        String code = "UPD-CUST-" + UUID.randomUUID().toString().substring(0, 4);
        CustomerDto created = customerService.createCustomer(CreateCustomerRequest.builder()
                .code(code)
                .name("Initial Customer")
                .build());

        CustomerDto updated = customerService.updateCustomer(created.getId(), UpdateCustomerRequest.builder()
                .name("Modified Customer")
                .creditDays(60)
                .build());

        assertThat(updated.getName()).isEqualTo("Modified Customer");
        assertThat(updated.getCreditDays()).isEqualTo(60);

        customerService.deleteCustomer(created.getId());

        CustomerDto deleted = customerService.getCustomer(created.getId());
        assertThat(deleted.getStatus()).isEqualTo("inactive");
    }
}
