package in.boss.inventory.domain.impl;

import in.boss.inventory.domain.LotStatus;
import in.boss.inventory.domain.MovementType;
import in.boss.inventory.persistence.LotRow;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class InventoryLotSnapshots {

    private InventoryLotSnapshots() {}

    static Map<String, Object> lotSnapshot(LotRow lot) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", lot.id());
        snapshot.put("itemId", lot.itemId());
        snapshot.put("storeId", lot.storeId());
        snapshot.put("qty", lot.quantity());
        snapshot.put("status", LotStatus.toApi(lot.status()));
        return snapshot;
    }

    static Map<String, Object> movementSnapshot(
            UUID movementId, LotRow lot, MovementType type, BigDecimal qty) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", movementId);
        snapshot.put("lotId", lot.id());
        snapshot.put("itemId", lot.itemId());
        snapshot.put("movementType", type.dbValue());
        snapshot.put("qty", qty);
        return snapshot;
    }
}
