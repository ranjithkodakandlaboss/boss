package in.boss.masterdata;

import in.boss.masterdata.api.dto.CreateItemCategoryRequest;
import in.boss.masterdata.api.dto.CreateItemRequest;
import in.boss.masterdata.api.dto.CreateUomRequest;
import in.boss.masterdata.api.dto.ItemCategoryDto;
import in.boss.masterdata.api.dto.ItemDto;
import in.boss.masterdata.api.dto.UomDto;
import in.boss.masterdata.api.dto.UpdateItemRequest;
import in.boss.masterdata.domain.DuplicateEntityException;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.ItemCategoryService;
import in.boss.masterdata.domain.ItemService;
import in.boss.masterdata.domain.LifecycleException;
import in.boss.masterdata.domain.UomService;
import in.boss.masterdata.domain.ValidationException;
import in.boss.shared.config.ConfigService;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemCategoryCrudTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private ItemCategoryService categoryService;

    @Autowired
    private ItemService itemService;

    @Autowired
    private UomService uomService;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ConfigService configService;

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

    @Test
    void inactiveCategoryCannotBeUsedForNewItemsOrChildrenOrItemReactivation() {
        UomDto uom = uomService.createUom(CreateUomRequest.builder()
                .name("Each").symbol("EA-" + UUID.randomUUID().toString().substring(0, 4))
                .uomType("quantity").build());
        ItemCategoryDto category = categoryService.createCategory(CreateItemCategoryRequest.builder()
                .code("GUARD").name("Guard").build());
        ItemDto item = itemService.createItem(CreateItemRequest.builder()
                .code("GUARDED").name("Guarded item").itemType("RAW_MATERIAL")
                .uomId(uom.getId()).categoryId(category.getId()).build());
        itemService.deleteItem(item.getId());
        categoryService.deleteCategory(category.getId());
        ItemDto historical = itemService.updateItem(item.getId(), UpdateItemRequest.builder()
                .name("Historical item").categoryId(category.getId()).build());
        assertThat(historical.getCategoryLabel()).isEqualTo("Guard");

        assertThatThrownBy(() -> itemService.createItem(CreateItemRequest.builder()
                .code("NEW").name("New item").itemType("RAW_MATERIAL")
                .uomId(uom.getId()).categoryId(category.getId()).build()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("inactive");
        assertThatThrownBy(() -> itemService.updateItem(item.getId(), UpdateItemRequest.builder()
                .status("active").build()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("inactive");
        assertThatThrownBy(() -> categoryService.createCategory(CreateItemCategoryRequest.builder()
                .code("CHILD").name("Child").parentId(category.getId()).build()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("inactive");
        assertThat(itemService.getItem(item.getId()).getCategoryLabel()).isEqualTo("Guard");
    }

    @Test
    void categoryDeactivationWaitsForConcurrentItemCreation() throws Exception {
        UomDto uom = uomService.createUom(CreateUomRequest.builder()
                .name("Each").symbol("EA-" + UUID.randomUUID().toString().substring(0, 4))
                .uomType("quantity").build());
        ItemCategoryDto category = categoryService.createCategory(CreateItemCategoryRequest.builder()
                .code("RACE").name("Race category").build());
        CountDownLatch itemCreated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch deletionStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var creator = executor.submit(() -> {
                setTenantContext(tenantId, "OWNER");
                try {
                    transactions.executeWithoutResult(status -> {
                        itemService.createItem(CreateItemRequest.builder().code("RACE-ITEM").name("Race item")
                                .itemType("RAW_MATERIAL").uomId(uom.getId()).categoryId(category.getId()).build());
                        itemCreated.countDown();
                        try {
                            if (!release.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ex);
                        }
                    });
                } finally {
                    BossContextHolder.clear();
                }
            });
            try {
                assertThat(itemCreated.await(60, TimeUnit.SECONDS)).isTrue();
                var deletion = executor.submit(() -> {
                    setTenantContext(tenantId, "OWNER");
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                            "owner", null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
                    try {
                        deletionStarted.countDown();
                        configService.setOptionActive("ITEM_CATEGORY", category.getId(), false);
                    } finally {
                        SecurityContextHolder.clearContext();
                        BossContextHolder.clear();
                    }
                });
                assertThat(deletionStarted.await(60, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> deletion.get(500, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                release.countDown();
                creator.get(60, TimeUnit.SECONDS);
                assertThatThrownBy(() -> deletion.get(60, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(ResponseStatusException.class)
                        .hasRootCauseMessage("409 CONFLICT \"Cannot deactivate category used by 1 active item\"");
            } finally {
                release.countDown();
            }
        }
    }
}
