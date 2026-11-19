package in.boss.documents.api;

import in.boss.documents.api.DocumentDtos.ArchiveDocumentRequest;
import in.boss.documents.api.DocumentDtos.CreateDocumentRequest;
import in.boss.documents.api.DocumentDtos.DocumentHistoryEntry;
import in.boss.documents.api.DocumentDtos.DocumentResponse;
import in.boss.documents.api.DocumentDtos.PageResponse;
import in.boss.documents.api.DocumentDtos.RenewDocumentRequest;
import in.boss.documents.domain.DocumentVaultService;
import in.boss.documents.domain.DocumentVaultService.StoredDocumentFile;
import in.boss.shared.security.BossPermissions;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@PreAuthorize(BossPermissions.DOCUMENTS_READ)
public class DocumentController {

    private final DocumentVaultService documentService;

    public DocumentController(DocumentVaultService documentService) {
        this.documentService = documentService;
    }

    @GetMapping
    public PageResponse<DocumentResponse> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String attention,
            @RequestParam(required = false) Boolean includeArchived,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        String effectiveQuery = (query != null && !query.isBlank()) ? query : search;
        return documentService.listDocuments(
                category, status, attention, includeArchived,
                effectiveQuery, entityType, entityId, page, size);
    }

    @GetMapping("/{id}")
    public DocumentResponse get(@PathVariable UUID id) {
        return documentService.getDocument(id);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.DOCUMENTS_MANAGE)
    public DocumentResponse upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "metadata", required = false) CreateDocumentRequest metadata,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "docType", required = false) String docType,
            @RequestParam(value = "issueDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issueDate,
            @RequestParam(value = "expiryDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate,
            @RequestParam(value = "reminderDays", required = false) Integer reminderDays,
            @RequestParam(value = "certStatus", required = false) String certStatus,
            @RequestParam(value = "linkedEntityType", required = false) String linkedEntityType,
            @RequestParam(value = "linkedEntityId", required = false) UUID linkedEntityId,
            @RequestParam(value = "linkedEntityRef", required = false) String linkedEntityRef) {

        CreateDocumentRequest request = metadata != null ? metadata : new CreateDocumentRequest(
                title, category, docType, issueDate, expiryDate,
                reminderDays, certStatus, linkedEntityType, linkedEntityId, linkedEntityRef
        );

        return documentService.uploadDocument(request, file);
    }

    @PostMapping(value = "/{id}/renew", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(BossPermissions.DOCUMENTS_MANAGE)
    public DocumentResponse renew(
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "metadata", required = false) RenewDocumentRequest metadata,
            @RequestParam(value = "issueDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issueDate,
            @RequestParam(value = "expiryDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate,
            @RequestParam(value = "reminderDays", required = false) Integer reminderDays,
            @RequestParam(value = "notes", required = false) String notes) {

        RenewDocumentRequest request = metadata != null ? metadata : new RenewDocumentRequest(
                issueDate, expiryDate, reminderDays, notes
        );

        return documentService.renewDocument(id, request, file);
    }

    @PatchMapping("/{id}/archive")
    @PreAuthorize(BossPermissions.DOCUMENTS_MANAGE)
    public DocumentResponse archive(
            @PathVariable UUID id,
            @Valid @RequestBody ArchiveDocumentRequest request) {
        return documentService.archiveDocument(id, request);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer version) {

        StoredDocumentFile file = documentService.downloadDocument(id, version);

        HttpHeaders headers = new HttpHeaders();
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(file.contentType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        headers.setContentType(mediaType);
        headers.setContentDispositionFormData("inline", file.fileName());
        headers.setContentLength(file.content().length);

        return new ResponseEntity<>(file.content(), headers, HttpStatus.OK);
    }

    @GetMapping("/{id}/history")
    public List<DocumentHistoryEntry> history(@PathVariable UUID id) {
        return documentService.getDocumentHistory(id);
    }
}
