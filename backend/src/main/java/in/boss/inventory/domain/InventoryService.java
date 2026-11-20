package in.boss.inventory.domain;

import in.boss.inventory.api.InventoryApiDtos.AddMovementRequest;
import in.boss.inventory.api.InventoryApiDtos.CreateLotRequest;
import in.boss.inventory.api.InventoryApiDtos.CustomerPoReservationRequest;
import in.boss.inventory.api.InventoryApiDtos.CustomerPoReservationResponse;
import in.boss.inventory.api.InventoryApiDtos.InventoryPage;
import in.boss.inventory.api.InventoryApiDtos.JobReservationResponse;
import in.boss.inventory.api.InventoryApiDtos.InventoryPositionResponse;
import in.boss.inventory.api.InventoryApiDtos.IssueMaterialRequest;
import in.boss.inventory.api.InventoryApiDtos.MaterialLotResponse;
import in.boss.inventory.api.InventoryApiDtos.ReservationRequest;
import in.boss.inventory.api.InventoryApiDtos.ReservationResponse;
import in.boss.inventory.api.InventoryApiDtos.ReturnMaterialRequest;
import in.boss.inventory.api.InventoryApiDtos.LotReservationResponse;
import in.boss.inventory.api.InventoryApiDtos.StockMovementResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface InventoryService {

    default MaterialLotResponse createLot(CreateLotRequest request) {
        return createLot(request, null);
    }

    MaterialLotResponse createLot(CreateLotRequest request, String preferredLotNumber);

    StockMovementResponse addMovement(UUID lotId, AddMovementRequest request);

    ReservationResponse reserveForJob(ReservationRequest request, boolean allowPartial);

    ReservationResponse reserveForJobRequirements(UUID jobId, boolean allowPartial);

    CustomerPoReservationResponse reserveForCustomerPo(
            CustomerPoReservationRequest request, boolean allowPartial);

    in.boss.inventory.api.InventoryApiDtos.FeasibilityResponse checkAndReserveForCustomerPo(UUID cpoId);

    void releaseJobReservations(UUID jobId);

    void releaseCustomerPoReservations(UUID cpoId);

    StockMovementResponse issueMaterial(IssueMaterialRequest request);

    /**
     * Issues against this job's reservations without creating the job material issue row.
     * Use when the calling production workflow owns that row's additional traceability data.
     */
    StockMovementResponse issueReservedMaterial(IssueMaterialRequest request);

    StockMovementResponse returnMaterial(ReturnMaterialRequest request);

    MaterialLotResponse transitionLotStatus(UUID lotId, String targetStatus);

    InventoryPage<InventoryPositionResponse> getPosition(
            UUID storeId, UUID itemId, UUID siteId, boolean belowReorderPoint, int page, int size);

    InventoryPage<MaterialLotResponse> listLots(
            UUID itemId, UUID storeId, String status, int page, int size);

    MaterialLotResponse getLot(UUID lotId);

    InventoryPage<StockMovementResponse> getLotMovements(
            UUID lotId, LocalDate from, LocalDate to, int page, int size);

    List<JobReservationResponse> listActiveJobReservations();
}
