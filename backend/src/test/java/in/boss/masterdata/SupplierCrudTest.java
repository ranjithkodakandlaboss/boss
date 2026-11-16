package in.boss.masterdata;

import in.boss.masterdata.api.dto.ContactDto;
import in.boss.masterdata.api.dto.CreateSupplierRequest;
import in.boss.masterdata.api.dto.PageResponse;
import in.boss.masterdata.api.dto.SupplierDto;
import in.boss.masterdata.api.dto.UpdateSupplierRequest;
import in.boss.masterdata.domain.DuplicateEntityException;
import in.boss.masterdata.domain.SupplierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplierCrudTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private SupplierService supplierService;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = provisionTenant("supp-test");
        setTenantContext(tenantId, "OWNER");
    }

    @Test
    void createAndGetSupplier_withContacts_success() {
        String code = "SUPP-" + UUID.randomUUID().toString().substring(0, 6);
        CreateSupplierRequest request = CreateSupplierRequest.builder()
                .code(code)
                .name("Tata Steel Processing")
                .legalName("Tata Steel BSL Limited")
                .gstin("27AABCT3518Q1ZV")
                .address(Map.of("city", "Mumbai", "state", "Maharashtra"))
                .paymentTerms(30)
                .creditLimit(new BigDecimal("1000000.00"))
                .contacts(List.of(
                        ContactDto.builder()
                                .name("Anil Patil")
                                .role("Sales Manager")
                                .phone("+919812345678")
                                .email("anil.patil@tatasteel.example.com")
                                .isPrimary(true)
                                .build()
                ))
                .build();

        SupplierDto created = supplierService.createSupplier(request);
        assertThat(created.getId()).isNotNull();
        assertThat(created.getCode()).isEqualTo(code);
        assertThat(created.getGstin()).isEqualTo("27AABCT3518Q1ZV");
        assertThat(created.getContacts()).hasSize(1);
        assertThat(created.getContacts().get(0).getName()).isEqualTo("Anil Patil");

        SupplierDto fetched = supplierService.getSupplier(created.getId());
        assertThat(fetched.getId()).isEqualTo(created.getId());
        assertThat(fetched.getContacts()).hasSize(1);

        // Verify audit event
        int auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND entity_id = ?",
                Integer.class, tenantId, created.getId());
        assertThat(auditCount).isGreaterThan(0);
    }

    @Test
    void duplicateSupplierCode_throwsDuplicateEntityException() {
        String code = "DUP-SUPP";
        supplierService.createSupplier(CreateSupplierRequest.builder()
                .code(code)
                .name("Supplier Alpha")
                .build());

        assertThatThrownBy(() -> supplierService.createSupplier(CreateSupplierRequest.builder()
                .code(code)
                .name("Supplier Beta")
                .build()))
                .isInstanceOf(DuplicateEntityException.class)
                .hasMessageContaining("Supplier code already exists");
    }

    @Test
    void updateAndSoftDeleteSupplier() {
        String code = "UPD-SUPP-" + UUID.randomUUID().toString().substring(0, 4);
        SupplierDto created = supplierService.createSupplier(CreateSupplierRequest.builder()
                .code(code)
                .name("Old Supplier Name")
                .build());

        SupplierDto updated = supplierService.updateSupplier(created.getId(), UpdateSupplierRequest.builder()
                .name("New Supplier Name")
                .paymentTerms(45)
                .build());

        assertThat(updated.getName()).isEqualTo("New Supplier Name");
        assertThat(updated.getPaymentTerms()).isEqualTo(45);

        supplierService.deleteSupplier(created.getId());

        SupplierDto deleted = supplierService.getSupplier(created.getId());
        assertThat(deleted.getStatus()).isEqualTo("inactive");
    }
}
