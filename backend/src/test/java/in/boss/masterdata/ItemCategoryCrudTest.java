package in.boss.masterdata;

import in.boss.masterdata.api.dto.CreateItemCategoryRequest;
import in.boss.masterdata.api.dto.CreateItemRequest;
import in.boss.masterdata.api.dto.CreateUomRequest;
import in.boss.masterdata.api.dto.ItemCategoryDto;
import in.boss.masterdata.api.dto.ItemDto;
import in.boss.masterdata.api.dto.UomDto;
import in.boss.masterdata.domain.DuplicateEntityException;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.ItemCategoryService;
import in.boss.masterdata.domain.ItemService;
import in.boss.masterdata.domain.LifecycleException;
import in.boss.masterdata.domain.UomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemCategoryCrudTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private ItemCategoryService categoryService;

    @Autowired
    private ItemService itemService;

    @Autowired
    private UomService uomService;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = provisionTenant("cat-test");
        setTenantContext(tenantId, "OWNER");
    }

    @Test
    void createAndGetCategory_success() {
        String code = "CAT-" + UUID.randomUUID().toString().substring(0, 4);
        CreateItemCategoryRequest request = CreateItemCategoryRequest.builder()
                .code(code)
                .name("Fasteners & Hardware")
                .description("Bolts, nuts, rivets and washers")
                .build();

        ItemCategoryDto created = categoryService.createCategory(request);
        assertThat(created.getId()).isNotNull();
        assertThat(created.getCode()).isEqualTo(code);
        assertThat(created.getName()).isEqualTo("Fasteners & Hardware");
        assertThat(created.getIsActive()).isTrue();

        ItemCategoryDto fetched = categoryService.getCategory(created.getId());
        assertThat(fetched.getId()).isEqualTo(created.getId());
        assertThat(fetched.getName()).isEqualTo("Fasteners & Hardware");

        List<ItemCategoryDto> list = categoryService.listCategories();
        assertThat(list).anyMatch(c -> c.getId().equals(created.getId()));

        // Verify audit event
        int auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND entity_id = ?",
                Integer.class, tenantId, created.getId());
        assertThat(auditCount).isGreaterThan(0);
    }

    @Test
    void duplicateCategoryCode_throwsDuplicateEntityException() {
        String code = "DUP-CAT";
        categoryService.createCategory(CreateItemCategoryRequest.builder()
                .code(code)
                .name("Category Alpha")
                .build());

        assertThatThrownBy(() -> categoryService.createCategory(CreateItemCategoryRequest.builder()
                .code(code)
                .name("Category Beta")
                .build()))
                .isInstanceOf(DuplicateEntityException.class)
                .hasMessageContaining("Category code already exists");
    }

    @Test
    void deleteCategory_softDeletesAndRecordsAudit() {
        String code = "DEL-CAT-" + UUID.randomUUID().toString().substring(0, 4);
        ItemCategoryDto created = categoryService.createCategory(CreateItemCategoryRequest.builder()
                .code(code)
                .name("Temporary Category")
                .build());

        categoryService.deleteCategory(created.getId());

        // Inactive category should not appear in listCategories
        List<ItemCategoryDto> activeList = categoryService.listCategories();
        assertThat(activeList).noneMatch(c -> c.getId().equals(created.getId()));

        // Should still exist with isActive = false
        ItemCategoryDto fetched = categoryService.getCategory(created.getId());
        assertThat(fetched.getIsActive()).isFalse();

        // Audit delete event verified
        int deleteAudits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND entity_id = ? AND action = 'DELETE'",
                Integer.class, tenantId, created.getId());
        assertThat(deleteAudits).isEqualTo(1);
    }

    @Test
    void deleteCategory_whenInUseByActiveItem_throwsLifecycleException() {
        UomDto uom = uomService.createUom(CreateUomRequest.builder()
                .name("Kilogram")
                .symbol("KG-" + UUID.randomUUID().toString().substring(0, 4))
                .uomType("weight")
                .build());

        String catCode = "INUSE-CAT-" + UUID.randomUUID().toString().substring(0, 4);
        ItemCategoryDto cat = categoryService.createCategory(CreateItemCategoryRequest.builder()
                .code(catCode)
                .name("Raw Steels")
                .build());

        itemService.createItem(CreateItemRequest.builder()
                .code("ITEM-" + UUID.randomUUID().toString().substring(0, 4))
                .name("Steel Bar 10mm")
                .itemType("RAW_MATERIAL")
                .uomId(uom.getId())
                .categoryId(cat.getId())
                .build());

        assertThatThrownBy(() -> categoryService.deleteCategory(cat.getId()))
                .isInstanceOf(LifecycleException.class)
                .hasMessageContaining("Cannot delete category in use by active items");
    }

    @Test
    void itemCategoryLabelRemainsStableAfterCategoryRenameAndDeactivation() {
        UomDto uom = uomService.createUom(CreateUomRequest.builder()
                .name("Kilogram")
                .symbol("KG-" + UUID.randomUUID().toString().substring(0, 4))
                .uomType("weight")
                .build());
        ItemCategoryDto category = categoryService.createCategory(CreateItemCategoryRequest.builder()
                .code("SNAPSHOT-" + UUID.randomUUID().toString().substring(0, 6))
                .name("Raw Steel")
                .build());

        ItemDto item = itemService.createItem(CreateItemRequest.builder()
                .code("ITEM-" + UUID.randomUUID().toString().substring(0, 6))
                .name("Steel Bar")
                .itemType("RAW_MATERIAL")
                .uomId(uom.getId())
                .categoryId(category.getId())
                .build());

        jdbc.update("UPDATE item_categories SET name = ?, is_active = FALSE WHERE id = ?",
                "Metal Stock", category.getId());

        assertThat(itemService.getItem(item.getId()).getCategoryLabel()).isEqualTo("Raw Steel");
    }

    @Test
    void deleteCategory_whenHasActiveChildSubcategory_throwsLifecycleException() {
        String parentCode = "PARENT-" + UUID.randomUUID().toString().substring(0, 4);
        ItemCategoryDto parent = categoryService.createCategory(CreateItemCategoryRequest.builder()
                .code(parentCode)
                .name("Metals")
                .build());

        String childCode = "CHILD-" + UUID.randomUUID().toString().substring(0, 4);
        categoryService.createCategory(CreateItemCategoryRequest.builder()
                .code(childCode)
                .name("Alloy Steels")
                .parentId(parent.getId())
                .build());

        assertThatThrownBy(() -> categoryService.deleteCategory(parent.getId()))
                .isInstanceOf(LifecycleException.class)
                .hasMessageContaining("Cannot delete category with active child subcategories");
    }
}
