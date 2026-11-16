package in.boss.masterdata.domain;

public class CircularReferenceException extends MasterDataException {
    public CircularReferenceException(String message) {
        super(message);
    }
}
