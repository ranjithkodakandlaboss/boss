package in.boss.masterdata.domain;

import in.boss.masterdata.api.dto.CreateItemCategoryRequest;
import in.boss.masterdata.api.dto.ItemCategoryDto;

import java.util.List;
import java.util.UUID;

public interface ItemCategoryService {
    List<ItemCategoryDto> listCategories();
    ItemCategoryDto getCategory(UUID id);
    ItemCategoryDto createCategory(CreateItemCategoryRequest request);
    void deleteCategory(UUID id);
}
