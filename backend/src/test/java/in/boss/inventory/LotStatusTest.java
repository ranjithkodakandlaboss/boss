package in.boss.inventory;

import in.boss.inventory.api.InventoryApiDtos.AddMovementRequest;
import in.boss.inventory.api.InventoryApiDtos.CreateLotRequest;
import in.boss.inventory.api.InventoryApiDtos.InventoryPositionResponse;
import in.boss.inventory.api.InventoryApiDtos.MaterialLotResponse;
import in.boss.inventory.domain.InvalidLotTransitionException;
import in.boss.inventory.domain.InventoryService;
import in.boss.inventory.domain.InventoryValidationException;
import in.boss.inventory.domain.LotNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LotStatusTest extends InventoryIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    private Seed seed;

    @BeforeEach
    void setUp() {
        seed = seed("lot-status");
    }

    @Test
    void createLot_startsAvailable_andWritesOpeningMovement() {
        MaterialLotResponse lot = inventoryService.createLot(lot(new BigDecimal("10"), "2.5000", null, null));

        assertThat(lot.status()).isEqualTo("available");
        assertThat(lot.qty()).isEqualByComparingTo("10.000");
        assertThat(lot.reservedQty()).isEqualByComparingTo("0");
        assertThat(lot.availableQty()).isEqualByComparingTo("10.000");
        assertThat(lot.itemCode()).startsWith("ITM-");
        assertThat(lot.storeName()).isEqualTo("Raw Store");

        var movements = inventoryService.getLotMovements(lot.id(), null, null, 0, 20);
        assertThat(movements.totalElements()).isEqualTo(1);
        assertThat(movements.content().get(0).movementType()).isEqualTo("opening_balance");
        assertThat(movements.content().get(0).qty()).isEqualByComparingTo("10.000");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND entity_type = 'LOT' AND action = 'CREATE'",
                Integer.class, seed.tenantId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'inventory.lot.created'",
                Integer.class, seed.tenantId())).isEqualTo(1);
    }

    @Test
    void quarantineHold_canBeReleased_andIsExcludedFromPosition() {
        MaterialLotResponse lot = inventoryService.createLot(lot(new BigDecimal("6"), "1", null, null));

        MaterialLotResponse held = inventoryService.transitionLotStatus(lot.id(), "quarantine");
        assertThat(held.status()).isEqualTo("quarantine");
        assertThat(inventoryService.getPosition(null, seed.itemId(), null, false, 0, 20).content()).isEmpty();

        MaterialLotResponse released = inventoryService.transitionLotStatus(lot.id(), "available");
        assertThat(released.status()).isEqualTo("available");
        assertThat(inventoryService.getPosition(null, seed.itemId(), null, false, 0, 20).content())
                .singleElement()
                .extracting(InventoryPositionResponse::availableQty)
                .satisfies(qty -> assertThat((BigDecimal) qty).isEqualByComparingTo("6.000"));
    }

    @Test
    void explicitTransition_rejectsConsumedAndDisposedWhileQuantityRemains() {
        MaterialLotResponse lot = inventoryService.createLot(lot(new BigDecimal("4"), "1", null, null));

        assertThatThrownBy(() -> inventoryService.transitionLotStatus(lot.id(), "consumed"))
                .isInstanceOf(InvalidLotTransitionException.class);
        assertThatThrownBy(() -> inventoryService.transitionLotStatus(lot.id(), "depleted"))
                .isInstanceOf(InvalidLotTransitionException.class);
        assertThatThrownBy(() -> inventoryService.transitionLotStatus(lot.id(), "disposed"))
                .isInstanceOf(InvalidLotTransitionException.class);
        assertThatThrownBy(() -> inventoryService.createLot(lot(new BigDecimal("1"), "1", null, "depleted")))
                .isInstanceOf(InventoryValidationException.class);
        assertThat(inventoryService.getLot(lot.id()).status()).isEqualTo("available");
    }

    @Test
    void fullyReservedLot_remainsInPositionWithoutDoubleCounting() {
        inventoryService.createLot(lot(new BigDecimal("10"), "2.00", null, null));
        MaterialLotResponse reservedLot = inventoryService.createLot(lot(new BigDecimal("5"), "2.00", null, null));
        jdbc.update("UPDATE material_lots SET reserved_qty = quantity WHERE id = ?", reservedLot.id());
        inventoryService.transitionLotStatus(reservedLot.id(), "reserved");

        InventoryPositionResponse position = inventoryService
                .getPosition(seed.storeId(), seed.itemId(), null, false, 0, 20)
                .content().get(0);
        assertThat(position.totalQty()).isEqualByComparingTo("15.000");
        assertThat(position.reservedQty()).isEqualByComparingTo("5.000");
        assertThat(position.availableQty()).isEqualByComparingTo("10.000");
        assertThat(position.valuationAmt()).isEqualByComparingTo("30.0000");
        assertThat(position.lastMovedAt()).isNotNull();
    }

    @Test
    void grnLine_mustExistInTenantAndMatchItemAndStore() {
        assertThatThrownBy(() -> inventoryService.createLot(
                lotWithGrn(seed.itemId(), seed.storeId(), UUID.randomUUID())))
                .isInstanceOf(InventoryValidationException.class)
                .hasMessageContaining("GRN line not found");

        UUID otherItem = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id)
                VALUES (?, ?, 'Other Item', 'raw_material', ?) RETURNING id
                """, seed.tenantId(), "ITM-" + UUID.randomUUID().toString().substring(0, 8), seed.uomId());
        UUID mismatchedItemLine = insertGrnLine(seed, otherItem, seed.storeId());
        assertThatThrownBy(() -> inventoryService.createLot(
                lotWithGrn(seed.itemId(), seed.storeId(), mismatchedItemLine)))
                .isInstanceOf(InventoryValidationException.class)
                .hasMessageContaining("does not match");

        UUID otherStore = insert("""
                INSERT INTO stores (tenant_id, site_id, name, code, store_type)
                VALUES (?, ?, 'Other Store', ?, 'raw_material') RETURNING id
                """, seed.tenantId(), seed.siteId(), "OS-" + UUID.randomUUID().toString().substring(0, 8));
        UUID mismatchedStoreLine = insertGrnLine(seed, seed.itemId(), otherStore);
        assertThatThrownBy(() -> inventoryService.createLot(
                lotWithGrn(seed.itemId(), seed.storeId(), mismatchedStoreLine)))
                .isInstanceOf(InventoryValidationException.class)
                .hasMessageContaining("does not match");

        UUID matchingLine = insertGrnLine(seed, seed.itemId(), seed.storeId());
        MaterialLotResponse created = inventoryService.createLot(
                lotWithGrn(seed.itemId(), seed.storeId(), matchingLine));
        assertThat(created.grnLineId()).isEqualTo(matchingLine);
        assertThat(inventoryService.getLotMovements(created.id(), null, null, 0, 5).content())
                .singleElement()
                .extracting(movement -> movement.movementType())
                .isEqualTo("receipt");

        Seed otherTenant = seed("grn-other");
        setTenantContext(otherTenant.tenantId(), "STORE_KEEPER");
        assertThatThrownBy(() -> inventoryService.createLot(new CreateLotRequest(
                otherTenant.itemId(), otherTenant.storeId(), new BigDecimal("1"), new BigDecimal("1"),
                null, null, null, matchingLine)))
                .isInstanceOf(InventoryValidationException.class)
                .hasMessageContaining("GRN line not found");
    }

    @Test
    void issueToZero_becomesDepleted_andOnlyInboundRestoresIt() {
        MaterialLotResponse lot = inventoryService.createLot(lot(new BigDecimal("8"), "2.50", "BATCH-1", null));

        var issued = inventoryService.addMovement(lot.id(), movement("issue", "8"));
        assertThat(issued.qty()).isEqualByComparingTo("-8.000");
        assertThat(issued.movementType()).isEqualTo("issue");

        MaterialLotResponse depleted = inventoryService.getLot(lot.id());
        assertThat(depleted.status()).isEqualTo("depleted");
        assertThat(depleted.qty()).isEqualByComparingTo("0.000");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM material_lots WHERE id = ?", String.class, lot.id())).isEqualTo("consumed");
        assertThatThrownBy(() -> inventoryService.transitionLotStatus(lot.id(), "available"))
                .isInstanceOf(InvalidLotTransitionException.class);

        inventoryService.addMovement(lot.id(), movement("return", "1.5"));
        MaterialLotResponse restored = inventoryService.getLot(lot.id());
        assertThat(restored.status()).isEqualTo("available");
        assertThat(restored.qty()).isEqualByComparingTo("1.500");
    }

    @Test
    void scrapToZero_becomesDisposed_andRejectsFurtherMovements() {
        MaterialLotResponse lot = inventoryService.createLot(lot(new BigDecimal("3"), "1", null, null));
        inventoryService.addMovement(lot.id(), movement("scrap", "3"));

        assertThat(inventoryService.getLot(lot.id()).status()).isEqualTo("disposed");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM material_lots WHERE id = ?", String.class, lot.id())).isEqualTo("disposed");
        assertThatThrownBy(() -> inventoryService.addMovement(lot.id(), movement("receipt", "1")))
                .isInstanceOf(InvalidLotTransitionException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE lot_id = ?", Integer.class, lot.id())).isEqualTo(2);
    }

    @Test
    void reservedTransition_requiresTheLotToBeFullyReserved() {
        MaterialLotResponse lot = inventoryService.createLot(lot(new BigDecimal("5"), "1", null, null));

        assertThatThrownBy(() -> inventoryService.transitionLotStatus(lot.id(), "reserved"))
                .isInstanceOf(InvalidLotTransitionException.class);

        jdbc.update("UPDATE material_lots SET reserved_qty = quantity WHERE id = ?", lot.id());
        assertThat(inventoryService.transitionLotStatus(lot.id(), "reserved").status()).isEqualTo("reserved");
        assertThatThrownBy(() -> inventoryService.transitionLotStatus(lot.id(), "available"))
                .isInstanceOf(InvalidLotTransitionException.class);

        jdbc.update("UPDATE material_lots SET reserved_qty = 0 WHERE id = ?", lot.id());
        assertThat(inventoryService.transitionLotStatus(lot.id(), "available").status()).isEqualTo("available");
    }

    @Test
    void outboundBeyondUnreservedQuantity_doesNotAppendAMovement() {
        MaterialLotResponse lot = inventoryService.createLot(lot(new BigDecimal("2"), "1", null, null));

        assertThatThrownBy(() -> inventoryService.addMovement(lot.id(), movement("issue", "2.001")))
                .isInstanceOf(InventoryValidationException.class);
        assertThat(inventoryService.getLot(lot.id()).qty()).isEqualByComparingTo("2.000");
        assertThat(inventoryService.getLotMovements(lot.id(), null, null, 0, 20).totalElements()).isEqualTo(1);
    }

    @Test
    void stockMovementInsertedByTheService_cannotBeUpdated() {
        MaterialLotResponse lot = inventoryService.createLot(lot(new BigDecimal("1"), "1", null, null));
        UUID movementId = jdbc.queryForObject(
                "SELECT id FROM stock_movements WHERE lot_id = ?", UUID.class, lot.id());

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE stock_movements SET quantity = 9 WHERE id = ?", movementId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM stock_movements WHERE id = ?", movementId))
                .hasMessageContaining("append-only");
    }

    @Test
    void getPosition_sumsAvailableLotsAndValuation() {
        inventoryService.createLot(lot(new BigDecimal("10"), "2.50", null, null));
        inventoryService.createLot(lot(new BigDecimal("4"), "2.50", null, null));
        MaterialLotResponse quarantined = inventoryService.createLot(
                lot(new BigDecimal("100"), "9", null, "quarantine"));
        assertThat(quarantined.status()).isEqualTo("quarantine");

        InventoryPositionResponse position = inventoryService
                .getPosition(seed.storeId(), seed.itemId(), seed.siteId(), false, 0, 20)
                .content().get(0);
        assertThat(position.totalQty()).isEqualByComparingTo("14.000");
        assertThat(position.availableQty()).isEqualByComparingTo("14.000");
        assertThat(position.reservedQty()).isEqualByComparingTo("0");
        assertThat(position.valuationAmt()).isEqualByComparingTo("35.0000");
        assertThat(position.lastMovedAt()).isNotNull();
        assertThat(inventoryService.getPosition(null, seed.itemId(), null, true, 0, 20).totalElements())
                .isEqualTo(1);

        inventoryService.addMovement(
                inventoryService.listLots(seed.itemId(), seed.storeId(), "available", 0, 10)
                        .content().get(0).id(),
                movement("receipt", "20"));
        assertThat(inventoryService.getPosition(null, seed.itemId(), null, true, 0, 20).content()).isEmpty();
    }

    @Test
    void otherTenant_cannotReadTheLotOrItsPosition() {
        MaterialLotResponse lot = inventoryService.createLot(lot(new BigDecimal("1"), "1", null, null));
        UUID otherTenant = provisionTenant("lot-status-other");
        setTenantContext(otherTenant, "OWNER");

        assertThatThrownBy(() -> inventoryService.getLot(lot.id()))
                .isInstanceOf(LotNotFoundException.class);
        assertThatThrownBy(() -> inventoryService.addMovement(lot.id(), movement("receipt", "1")))
                .isInstanceOf(LotNotFoundException.class);
        assertThat(inventoryService.getPosition(null, null, null, false, 0, 20).content()).isEmpty();
    }

    @Test
    void movementHistory_filtersByDate_andUnknownTypeIsRejected() {
        MaterialLotResponse lot = inventoryService.createLot(lot(new BigDecimal("5"), "1", null, null));
        inventoryService.addMovement(lot.id(), movement("adjustment_add", "1"));

        LocalDate today = LocalDate.now();
        assertThat(inventoryService.getLotMovements(lot.id(), today, today, 0, 20).totalElements()).isEqualTo(2);
        assertThat(inventoryService.getLotMovements(lot.id(), today.plusDays(1), today.plusDays(1), 0, 20)
                .totalElements()).isZero();
        assertThatThrownBy(() -> inventoryService.addMovement(lot.id(), movement("ADJUSTMENT", "1")))
                .isInstanceOf(InventoryValidationException.class);
    }

    private CreateLotRequest lot(BigDecimal qty, String unitCost, String batch, String status) {
        return new CreateLotRequest(
                seed.itemId(),
                seed.storeId(),
                qty,
                new BigDecimal(unitCost),
                batch,
                LocalDate.of(2026, 12, 31),
                status,
                null);
    }

    private static AddMovementRequest movement(String type, String qty) {
        return new AddMovementRequest(type, new BigDecimal(qty), null, null, null);
    }

    private CreateLotRequest lotWithGrn(UUID itemId, UUID storeId, UUID grnLineId) {
        return new CreateLotRequest(
                itemId, storeId, new BigDecimal("2"), new BigDecimal("1"), null, null, "available", grnLineId);
    }

}
