package in.boss.inventory.domain;

/**
 * Movement types persisted by V016. Quantity on the row is always positive.
 * Direction decides whether the lot quantity rises or falls.
 */
public enum MovementType {
    RECEIPT("receipt", true),
    ISSUE("issue", false),
    RETURN("return", true),
    TRANSFER_OUT("transfer_out", false),
    TRANSFER_IN("transfer_in", true),
    ADJUSTMENT_ADD("adjustment_add", true),
    ADJUSTMENT_REDUCE("adjustment_reduce", false),
    OPENING_BALANCE("opening_balance", true),
    SCRAP("scrap", false),
    CONSUME("consume", false);

    private final String dbValue;
    private final boolean inbound;

    MovementType(String dbValue, boolean inbound) {
        this.dbValue = dbValue;
        this.inbound = inbound;
    }

    public String dbValue() {
        return dbValue;
    }

    public boolean inbound() {
        return inbound;
    }

    public static MovementType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InventoryValidationException("Movement type is required");
        }
        String token = raw.trim().toLowerCase();
        for (MovementType type : values()) {
            if (type.dbValue.equals(token)) {
                return type;
            }
        }
        throw new InventoryValidationException("Unknown movement type: " + raw);
    }
}
