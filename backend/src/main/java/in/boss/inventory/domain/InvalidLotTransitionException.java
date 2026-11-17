package in.boss.inventory.domain;

public class InvalidLotTransitionException extends RuntimeException {

    public InvalidLotTransitionException(String message) {
        super(message);
    }
}
