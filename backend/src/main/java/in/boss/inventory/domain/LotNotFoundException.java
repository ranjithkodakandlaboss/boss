package in.boss.inventory.domain;

import java.util.UUID;

public class LotNotFoundException extends RuntimeException {

    public LotNotFoundException(UUID lotId) {
        super("Lot not found: " + lotId);
    }
}
