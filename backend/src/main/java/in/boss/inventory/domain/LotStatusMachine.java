package in.boss.inventory.domain;

import java.math.BigDecimal;

/**
 * Server-side lot status transitions allowed by the V016 status check.
 *
 * <p>Explicit transitions cover holds and reservation flags. Movement-driven
 * transitions cover quantity reaching zero, scrap, and a later inbound receipt
 * that restores a consumed lot. {@code disposed} is terminal.
 */
public final class LotStatusMachine {

    private LotStatusMachine() {}

    public static void assertExplicitTransition(
            String from, String to, BigDecimal quantity, BigDecimal reservedQty) {
        if (from.equals(to)) {
            throw new InvalidLotTransitionException("Lot is already " + from);
        }
        if (!allowed(from, to, quantity, reservedQty)) {
            throw new InvalidLotTransitionException(
                    "Cannot change lot status from " + from + " to " + LotStatus.toApi(to));
        }
    }

    public static String statusAfterMovement(
            String current, MovementType type, BigDecimal newQty, BigDecimal reservedQty) {
        if (LotStatus.DISPOSED.equals(current)) {
            throw new InvalidLotTransitionException("Disposed lots cannot accept stock movements");
        }
        if (newQty.signum() == 0) {
            String target = type == MovementType.SCRAP ? LotStatus.DISPOSED : LotStatus.CONSUMED;
            assertExplicitTransition(current, target, newQty, reservedQty);
            return target;
        }
        if (LotStatus.CONSUMED.equals(current)) {
            if (!type.inbound()) {
                throw new InvalidLotTransitionException("Consumed lots only accept inbound movements");
            }
            return LotStatus.AVAILABLE;
        }
        if (LotStatus.RESERVED.equals(current) && reservedQty.compareTo(newQty) < 0) {
            return LotStatus.AVAILABLE;
        }
        return current;
    }

    private static boolean allowed(String from, String to, BigDecimal quantity, BigDecimal reservedQty) {
        return switch (from) {
            case LotStatus.AVAILABLE -> fromAvailable(to, quantity, reservedQty);
            case LotStatus.QUARANTINE -> fromQuarantine(to, quantity);
            case LotStatus.RESERVED -> fromReserved(to, quantity, reservedQty);
            default -> false;
        };
    }

    private static boolean fromAvailable(String to, BigDecimal quantity, BigDecimal reservedQty) {
        return switch (to) {
            case LotStatus.QUARANTINE -> quantity.signum() > 0 && reservedQty.signum() == 0;
            case LotStatus.RESERVED -> quantity.signum() > 0 && reservedQty.compareTo(quantity) == 0;
            case LotStatus.CONSUMED, LotStatus.DISPOSED -> quantity.signum() == 0;
            default -> false;
        };
    }

    private static boolean fromQuarantine(String to, BigDecimal quantity) {
        return switch (to) {
            case LotStatus.AVAILABLE -> quantity.signum() > 0;
            case LotStatus.CONSUMED, LotStatus.DISPOSED -> quantity.signum() == 0;
            default -> false;
        };
    }

    private static boolean fromReserved(String to, BigDecimal quantity, BigDecimal reservedQty) {
        return switch (to) {
            case LotStatus.AVAILABLE -> quantity.signum() > 0 && reservedQty.signum() == 0;
            case LotStatus.CONSUMED, LotStatus.DISPOSED -> quantity.signum() == 0;
            default -> false;
        };
    }
}
