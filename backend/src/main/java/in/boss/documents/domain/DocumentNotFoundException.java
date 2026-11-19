package in.boss.documents.domain;

import java.util.UUID;

public class DocumentNotFoundException extends RuntimeException {
    public DocumentNotFoundException(UUID id) {
        super("Document not found: " + id);
    }

    public DocumentNotFoundException(String message) {
        super(message);
    }
}
