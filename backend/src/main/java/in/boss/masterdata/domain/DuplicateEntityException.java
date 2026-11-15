package in.boss.masterdata.domain;

public class DuplicateEntityException extends MasterDataException {
    public DuplicateEntityException(String message) {
        super(message);
    }
}
