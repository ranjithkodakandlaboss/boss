package in.boss.masterdata;

import in.boss.masterdata.api.dto.CreateItemCategoryRequest;
import in.boss.masterdata.api.dto.ItemCategoryDto;
import in.boss.masterdata.domain.DuplicateEntityException;
import in.boss.masterdata.domain.ItemCategoryService;
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
}
