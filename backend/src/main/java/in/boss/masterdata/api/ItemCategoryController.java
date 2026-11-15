package in.boss.masterdata.api;

import in.boss.masterdata.api.dto.CreateItemCategoryRequest;
import in.boss.masterdata.api.dto.ItemCategoryDto;
import in.boss.masterdata.domain.ItemCategoryService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class ItemCategoryController {

    private final ItemCategoryService categoryService;

    public ItemCategoryController(ItemCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping("/config/options/ITEM_CATEGORY")
    @PreAuthorize(BossPermissions.ANY_TENANT_USER)
    public ResponseEntity<List<Map<String, Object>>> getItemCategoryConfigOptions() {
        List<ItemCategoryDto> categories = categoryService.listCategories();
        List<Map<String, Object>> options = categories.stream()
                .map(c -> Map.of(
                        "id", (Object) c.getId(),
                        "code", c.getCode(),
                        "label", c.getName(),
                        "description", c.getDescription() != null ? c.getDescription() : "",
                        "isActive", Boolean.TRUE.equals(c.getIsActive())
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(options);
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
