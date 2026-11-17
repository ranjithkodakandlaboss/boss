package in.boss.inventory.domain;

public class InventoryValidationException extends RuntimeException {

    public InventoryValidationException(String message) {
        super(message);
    }
}
