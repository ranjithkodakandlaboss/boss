package in.boss.masterdata;

import in.boss.masterdata.api.dto.CreateItemRequest;
import in.boss.masterdata.api.dto.CreateUomRequest;
import in.boss.masterdata.api.dto.ItemDto;
import in.boss.masterdata.api.dto.PageResponse;
import in.boss.masterdata.api.dto.UomDto;
import in.boss.masterdata.api.dto.UpdateItemRequest;
import in.boss.masterdata.domain.DuplicateEntityException;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.ItemService;
import in.boss.masterdata.domain.UomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemCrudTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private ItemService itemService;

    @Autowired
    private UomService uomService;

    private UUID tenantId;
    private UUID uomId;

    @BeforeEach
    void setUp() {
        tenantId = provisionTenant("item-test");
        setTenantContext(tenantId, "OWNER");

        UomDto uom = uomService.createUom(CreateUomRequest.builder()
                .name("Kilogram")
                .symbol("KG-" + UUID.randomUUID().toString().substring(0, 4))
                .uomType("weight")
                .isBase(true)
                .build());
        uomId = uom.getId();
    }

    @Test
    void createAndGetItem_success() {
        CreateItemRequest request = CreateItemRequest.builder()
                .code("STEEL-001")
                .name("Cold Rolled Steel Sheet")
                .description("0.8mm CR sheet")
                .itemType("RAW_MATERIAL")
                .uomId(uomId)
                .standardCost(new BigDecimal("75.50"))
                .reorderPoint(new BigDecimal("100.000"))
                .build();

        ItemDto created = itemService.createItem(request);
        assertThat(created.getId()).isNotNull();
        assertThat(created.getCode()).isEqualTo("STEEL-001");
        assertThat(created.getName()).isEqualTo("Cold Rolled Steel Sheet");
        assertThat(created.getItemType()).isEqualTo("RAW_MATERIAL");
        assertThat(created.getStatus()).isEqualTo("active");

        ItemDto fetched = itemService.getItem(created.getId());
        assertThat(fetched.getId()).isEqualTo(created.getId());
        assertThat(fetched.getCode()).isEqualTo("STEEL-001");

        // Verify audit event written
        int auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND entity_id = ?",
                Integer.class, tenantId, created.getId());
        assertThat(auditCount).isGreaterThan(0);

        // Verify outbox event written
        int outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE tenant_id = ? AND aggregate_id = ?",
                Integer.class, tenantId, created.getId());
        assertThat(outboxCount).isGreaterThan(0);
    }

    @Test
    void duplicateCode_throwsDuplicateEntityException() {
        CreateItemRequest request = CreateItemRequest.builder()
                .code("DUP-001")
                .name("Test Item 1")
                .itemType("RAW_MATERIAL")
                .uomId(uomId)
                .build();

        itemService.createItem(request);

        CreateItemRequest duplicate = CreateItemRequest.builder()
                .code("DUP-001")
                .name("Test Item 2")
                .itemType("RAW_MATERIAL")
                .uomId(uomId)
                .build();

        assertThatThrownBy(() -> itemService.createItem(duplicate))
                .isInstanceOf(DuplicateEntityException.class)
                .hasMessageContaining("Item code already exists");
    }

    @Test
    void updateItem_modifiesFields() {
        ItemDto created = itemService.createItem(CreateItemRequest.builder()
                .code("UPD-001")
                .name("Original Name")
                .itemType("CONSUMABLE")
                .uomId(uomId)
                .standardCost(new BigDecimal("10.00"))
                .build());

        ItemDto updated = itemService.updateItem(created.getId(), UpdateItemRequest.builder()
                .name("Updated Name")
                .standardCost(new BigDecimal("15.50"))
                .build());

        assertThat(updated.getName()).isEqualTo("Updated Name");
        assertThat(updated.getStandardCost()).isEqualByComparingTo("15.50");
    }

    @Test
    void softDelete_deactivatesItem_andExcludesFromDefaultList() {
        ItemDto created = itemService.createItem(CreateItemRequest.builder()
                .code("DEL-001")
                .name("To Be Deleted")
                .itemType("RAW_MATERIAL")
                .uomId(uomId)
                .build());

        itemService.deleteItem(created.getId());

        ItemDto fetched = itemService.getItem(created.getId());
        assertThat(fetched.getStatus()).isEqualTo("inactive");

        // By default, listItems excludes inactive items
        PageResponse<ItemDto> list = itemService.listItems(null, null, null, "DEL-001", 0, 10);
        assertThat(list.getContent()).noneMatch(i -> i.getId().equals(created.getId()));

        // When explicitly filtering for status=inactive, it is returned
        PageResponse<ItemDto> inactiveList = itemService.listItems(null, null, "inactive", "DEL-001", 0, 10);
        assertThat(inactiveList.getContent()).anyMatch(i -> i.getId().equals(created.getId()));
    }
}
