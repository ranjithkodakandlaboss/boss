package in.boss.inventory.domain;

import in.boss.inventory.api.InventoryApiDtos.AddMovementRequest;
import in.boss.inventory.api.InventoryApiDtos.CreateLotRequest;
import in.boss.inventory.api.InventoryApiDtos.InventoryPage;
import in.boss.inventory.api.InventoryApiDtos.InventoryPositionResponse;
import in.boss.inventory.api.InventoryApiDtos.MaterialLotResponse;
import in.boss.inventory.api.InventoryApiDtos.StockMovementResponse;

import java.time.LocalDate;
import java.util.UUID;

public interface InventoryService {

    MaterialLotResponse createLot(CreateLotRequest request);

    StockMovementResponse addMovement(UUID lotId, AddMovementRequest request);

    MaterialLotResponse transitionLotStatus(UUID lotId, String targetStatus);

    InventoryPage<InventoryPositionResponse> getPosition(
            UUID storeId, UUID itemId, UUID siteId, boolean belowReorderPoint, int page, int size);

    InventoryPage<MaterialLotResponse> listLots(
            UUID itemId, UUID storeId, String status, int page, int size);

    MaterialLotResponse getLot(UUID lotId);

    InventoryPage<StockMovementResponse> getLotMovements(
            UUID lotId, LocalDate from, LocalDate to, int page, int size);
}
