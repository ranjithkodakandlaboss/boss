package in.boss.masterdata.domain;

import in.boss.masterdata.api.dto.CreateItemRequest;
import in.boss.masterdata.api.dto.ItemDto;
import in.boss.masterdata.api.dto.PageResponse;
import in.boss.masterdata.api.dto.UpdateItemRequest;

import java.util.UUID;

public interface ItemService {
    ItemDto createItem(CreateItemRequest request);
    ItemDto getItem(UUID id);
    ItemDto updateItem(UUID id, UpdateItemRequest request);
    void deleteItem(UUID id);
    PageResponse<ItemDto> listItems(String itemType, UUID categoryId, String status, String q, int page, int size);
}
