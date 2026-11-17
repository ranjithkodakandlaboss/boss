package in.boss.inventory.domain;

/**
 * Lot statuses persisted by V016.
 *
 * The API name for stored {@code consumed} is {@code depleted}. {@code disposed} is stored
 * and returned unchanged. Creation allows only {@code available} and {@code quarantine}.
 */
public final class LotStatus {

    public static final String AVAILABLE = "available";
    public static final String QUARANTINE = "quarantine";
    public static final String RESERVED = "reserved";
    public static final String CONSUMED = "consumed";
    public static final String DISPOSED = "disposed";
    public static final String API_DEPLETED = "depleted";

    private LotStatus() {}

    public static String canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return AVAILABLE;
        }
        String status = raw.trim().toLowerCase();
        if (API_DEPLETED.equals(status)) {
            return CONSUMED;
        }
        if (!isPersisted(status)) {
            throw new InventoryValidationException("Unknown lot status: " + raw);
        }
        return status;
    }

    public static String toApi(String persisted) {
        return CONSUMED.equals(persisted) ? API_DEPLETED : persisted;
    }

    public static void assertCreatable(String persisted) {
        if (!AVAILABLE.equals(persisted) && !QUARANTINE.equals(persisted)) {
            throw new InventoryValidationException(
                    "A new lot must start as available or quarantine");
        }
    }

    public static boolean isPersisted(String status) {
        return AVAILABLE.equals(status)
                || QUARANTINE.equals(status)
                || RESERVED.equals(status)
                || CONSUMED.equals(status)
                || DISPOSED.equals(status);
    }
}
