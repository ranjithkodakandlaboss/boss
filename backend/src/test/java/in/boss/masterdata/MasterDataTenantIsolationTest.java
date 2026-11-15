package in.boss.masterdata;

import in.boss.masterdata.api.dto.CreateCustomerRequest;
import in.boss.masterdata.api.dto.CreateItemRequest;
import in.boss.masterdata.api.dto.CreateSupplierRequest;
import in.boss.masterdata.api.dto.CreateUomRequest;
import in.boss.masterdata.api.dto.CustomerDto;
import in.boss.masterdata.api.dto.ItemDto;
import in.boss.masterdata.api.dto.PageResponse;
import in.boss.masterdata.api.dto.SupplierDto;
import in.boss.masterdata.api.dto.UomDto;
import in.boss.masterdata.domain.CustomerService;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.ItemService;
import in.boss.masterdata.domain.SupplierService;
import in.boss.masterdata.domain.UomService;
import in.boss.masterdata.domain.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MasterDataTenantIsolationTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private ItemService itemService;

    @Autowired
    private UomService uomService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private SupplierService supplierService;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        tenantA = provisionTenant("tenant-alpha");
        tenantB = provisionTenant("tenant-beta");
    }

    @Test
    void tenantB_cannotReadOrList_tenantA_items() {
        // As Tenant A
        setTenantContext(tenantA, "OWNER");
        UomDto uomA = uomService.createUom(CreateUomRequest.builder()
                .name("Unit Alpha")
                .symbol("UA-" + UUID.randomUUID().toString().substring(0, 4))
                .uomType("quantity")
                .build());

        ItemDto itemA = itemService.createItem(CreateItemRequest.builder()
                .code("ALPHA-ITEM-01")
                .name("Alpha Exclusive Item")
                .itemType("RAW_MATERIAL")
                .uomId(uomA.getId())
                .build());

        // Switch to Tenant B
        setTenantContext(tenantB, "OWNER");

        // 1. Direct get by ID must fail with 404 EntityNotFoundException
        assertThatThrownBy(() -> itemService.getItem(itemA.getId()))
                .isInstanceOf(EntityNotFoundException.class);

        // 2. Listing items must NOT contain Tenant A's item
        PageResponse<ItemDto> tenantBItems = itemService.listItems(null, null, null, null, 0, 50);
        assertThat(tenantBItems.getContent()).noneMatch(i -> i.getId().equals(itemA.getId()));

        // 3. Tenant B cannot create item referencing Tenant A's UOM
        assertThatThrownBy(() -> itemService.createItem(CreateItemRequest.builder()
                .code("BETA-ITEM-01")
                .name("Beta Item")
                .itemType("RAW_MATERIAL")
                .uomId(uomA.getId()) // UOM of Tenant A!
                .build()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid UOM ID");
    }

    @Test
    void tenantB_cannotReadOrList_tenantA_customersAndSuppliers() {
        // As Tenant A
        setTenantContext(tenantA, "OWNER");
        CustomerDto custA = customerService.createCustomer(CreateCustomerRequest.builder()
                .code("ALPHA-CUST")
                .name("Alpha Client")
                .build());

        SupplierDto suppA = supplierService.createSupplier(CreateSupplierRequest.builder()
                .code("ALPHA-SUPP")
                .name("Alpha Vendor")
                .build());

        // Switch to Tenant B
        setTenantContext(tenantB, "OWNER");

        // Direct get Customer
        assertThatThrownBy(() -> customerService.getCustomer(custA.getId()))
                .isInstanceOf(EntityNotFoundException.class);

        // Direct get Supplier
        assertThatThrownBy(() -> supplierService.getSupplier(suppA.getId()))
                .isInstanceOf(EntityNotFoundException.class);

        // List Customers
        PageResponse<CustomerDto> custs = customerService.listCustomers(null, null, 0, 50);
        assertThat(custs.getContent()).noneMatch(c -> c.getId().equals(custA.getId()));

        // List Suppliers
        PageResponse<SupplierDto> supps = supplierService.listSuppliers(null, null, 0, 50);
        assertThat(supps.getContent()).noneMatch(s -> s.getId().equals(suppA.getId()));
    }

    @Test
    void tenantB_cannotReadOrList_tenantA_uoms() {
        // As Tenant A
        setTenantContext(tenantA, "OWNER");
        String symA = "UOM-A-" + UUID.randomUUID().toString().substring(0, 4);
        UomDto uomA = uomService.createUom(CreateUomRequest.builder()
                .name("Alpha Meter")
                .symbol(symA)
                .uomType("length")
                .build());

        // Switch to Tenant B
        setTenantContext(tenantB, "OWNER");

        // Direct get
        assertThatThrownBy(() -> uomService.getUom(uomA.getId()))
                .isInstanceOf(EntityNotFoundException.class);

        // List UOMs
        List<UomDto> uoms = uomService.listUoms("length");
        assertThat(uoms).noneMatch(u -> u.getId().equals(uomA.getId()));
    }
}
