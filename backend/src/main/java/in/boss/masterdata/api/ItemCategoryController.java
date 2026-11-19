package in.boss.masterdata.api;

import in.boss.masterdata.api.dto.CreateItemCategoryRequest;
import in.boss.masterdata.api.dto.ItemCategoryDto;
import in.boss.masterdata.domain.ItemCategoryService;
import in.boss.shared.config.ConfigOptionDto;
import in.boss.shared.config.ConfigService;
import in.boss.shared.security.BossPermissions;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ItemCategoryController {

    private final ItemCategoryService categoryService;
    private final ConfigService configService;

    public ItemCategoryController(ItemCategoryService categoryService, ConfigService configService) {
        this.categoryService = categoryService;
        this.configService = configService;
    }

    @GetMapping("/config/options/ITEM_CATEGORY")
    @PreAuthorize(BossPermissions.ANY_TENANT_USER)
    public List<ConfigOptionDto> getItemCategoryConfigOptions(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return configService.getOptions("ITEM_CATEGORY", includeInactive);
    }

    @GetMapping("/item-categories")
    @PreAuthorize(BossPermissions.ANY_TENANT_USER)
    public ResponseEntity<List<ItemCategoryDto>> listCategories() {
        return ResponseEntity.ok(categoryService.listCategories());
    }

    @GetMapping("/item-categories/{id}")
    @PreAuthorize(BossPermissions.ANY_TENANT_USER)
    public ResponseEntity<ItemCategoryDto> getCategory(@PathVariable UUID id) {
        return ResponseEntity.ok(categoryService.getCategory(id));
    }

    @PostMapping("/item-categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.OWNER_OR_OPS)
    public ResponseEntity<ItemCategoryDto> createCategory(@Valid @RequestBody CreateItemCategoryRequest request) {
        ItemCategoryDto created = categoryService.createCategory(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/item-categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(BossPermissions.OWNER_OR_OPS)
    public ResponseEntity<Void> deleteCategory(@PathVariable UUID id) {
        categoryService.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }
}
