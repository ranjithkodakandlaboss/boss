package in.boss.documents.domain;

import in.boss.documents.api.DocumentDtos.ArchiveDocumentRequest;
import in.boss.documents.api.DocumentDtos.CreateDocumentRequest;
import in.boss.documents.api.DocumentDtos.DocumentHistoryEntry;
import in.boss.documents.api.DocumentDtos.DocumentResponse;
import in.boss.documents.api.DocumentDtos.LinkedEntityDto;
import in.boss.documents.api.DocumentDtos.PageResponse;
import in.boss.documents.api.DocumentDtos.RenewDocumentRequest;
import in.boss.documents.storage.StorageService;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import in.boss.shared.security.BossPermissions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@PreAuthorize(BossPermissions.DOCUMENTS_READ)
public class DocumentVaultService {

    public static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "Job Documents",
            "Quality Documents",
            "Material Certificates",
            "Supplier Documents",
            "Company Certificates",
            "Asset Documents"
    );

    private final JdbcTemplate jdbc;
    private final StorageService storageService;
    private final AuditService auditService;

    public DocumentVaultService(JdbcTemplate jdbc, StorageService storageService, AuditService auditService) {
        this.jdbc = jdbc;
        this.storageService = storageService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> listDocuments(
            String category,
            String status,
            String attention,
            Boolean includeArchived,
            String query,
            String entityType,
            UUID entityId,
            int page,
            int size) {

        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        StringBuilder sql = new StringBuilder("""
                SELECT d.id, d.title, d.doc_type, d.category, d.status,
                       d.issue_date, d.expiry_date, d.reminder_days, d.cert_status,
                       d.current_version, d.archived_at, d.archive_reason,
                       u.display_name AS uploader_name, u.email AS uploader_email,
                       au.display_name AS archiver_name, au.email AS archiver_email,
                       v.file_name, v.file_size_bytes, v.content_type, v.created_at AS version_created_at
                FROM vault_documents d
                LEFT JOIN app_users u ON u.id = d.created_by_id
                LEFT JOIN app_users au ON au.id = d.archived_by_id
                LEFT JOIN vault_document_versions v ON v.document_id = d.id AND v.version_number = d.current_version
                WHERE d.tenant_id = ?
                """);

        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        // Status / archived filter
        boolean showArchived = Boolean.TRUE.equals(includeArchived) || "archived".equalsIgnoreCase(status);
        if (showArchived) {
            if ("archived".equalsIgnoreCase(status)) {
                sql.append(" AND d.status = 'archived'");
            } else {
                sql.append(" AND d.status != 'superseded'");
            }
        } else {
            sql.append(" AND d.status NOT IN ('archived', 'superseded')");
        }

        // Category filter
        if (category != null && !category.isBlank() && !"All".equalsIgnoreCase(category)) {
            sql.append(" AND d.category = ?");
            params.add(category.trim());
        }

        // Attention / expiry filter
        if (attention != null && !attention.isBlank() && !"all".equalsIgnoreCase(attention)) {
            switch (attention.toLowerCase(Locale.ROOT)) {
                case "expired" -> sql.append(" AND d.expiry_date < CURRENT_DATE");
                case "expiring" -> sql.append(" AND d.expiry_date >= CURRENT_DATE AND d.expiry_date <= CURRENT_DATE + (d.reminder_days || ' days')::interval");
                case "due" -> sql.append(" AND d.expiry_date <= CURRENT_DATE + (d.reminder_days || ' days')::interval");
                default -> {}
            }
        }

        // Search query filter
        if (query != null && !query.isBlank()) {
            String q = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            sql.append(" AND (LOWER(d.title) LIKE ? OR LOWER(d.doc_type) LIKE ?)");
            params.add(q);
            params.add(q);
        }

        // Linked entity filter
        if (entityType != null && !entityType.isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM vault_document_links l WHERE l.document_id = d.id AND LOWER(l.entity_type) = ?");
            params.add(entityType.trim().toLowerCase(Locale.ROOT));
            if (entityId != null) {
                sql.append(" AND l.entity_id = ?");
                params.add(entityId);
            }
            sql.append(")");
        }

        // Count query
        String countSql = "SELECT COUNT(*) FROM (" + sql + ") AS filtered_docs";
        Long totalElements = jdbc.queryForObject(countSql, Long.class, params.toArray());
        long total = totalElements != null ? totalElements : 0;

        // Sorting & pagination
        sql.append(" ORDER BY d.created_at DESC LIMIT ? OFFSET ?");
        params.add(Math.max(1, size));
        params.add(Math.max(0, page) * size);

        List<DocumentResponse> content = jdbc.query(sql.toString(), (rs, i) -> mapDocumentSummary(rs, tenantId), params.toArray());

        return new PageResponse<>(content, page, size, total);
    }

    @Transactional(readOnly = true)
    public DocumentResponse getDocument(UUID documentId) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        List<DocumentResponse> docs = jdbc.query("""
                SELECT d.id, d.title, d.doc_type, d.category, d.status,
                       d.issue_date, d.expiry_date, d.reminder_days, d.cert_status,
                       d.current_version, d.archived_at, d.archive_reason,
                       u.display_name AS uploader_name, u.email AS uploader_email,
                       au.display_name AS archiver_name, au.email AS archiver_email,
                       v.file_name, v.file_size_bytes, v.content_type, v.created_at AS version_created_at
                FROM vault_documents d
                LEFT JOIN app_users u ON u.id = d.created_by_id
                LEFT JOIN app_users au ON au.id = d.archived_by_id
                LEFT JOIN vault_document_versions v ON v.document_id = d.id AND v.version_number = d.current_version
                WHERE d.tenant_id = ? AND d.id = ?
                """, (rs, i) -> mapDocumentSummary(rs, tenantId), tenantId, documentId);

        if (docs.isEmpty()) {
            throw new DocumentNotFoundException(documentId);
        }

        return docs.get(0);
    }

    @Transactional
    @PreAuthorize(BossPermissions.DOCUMENTS_MANAGE)
    public DocumentResponse uploadDocument(CreateDocumentRequest request, MultipartFile file) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();
        UUID userId = ctx.userId();

        validateUploadRequest(request, file);

        UUID linkedEntityId = request.linkedEntityId();
        String linkedEntityType = request.linkedEntityType();
        String linkedEntityRef = request.linkedEntityRef();
        if (linkedEntityType != null) {
            validateLinkedEntity(tenantId, linkedEntityType, linkedEntityId);
        }

        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String originalFileName = sanitizeFileName(file.getOriginalFilename());
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        long sizeBytes = file.getSize();

        // Calculate storage path: {tenantId}/documents/{documentId}/v1/{filename}
        String objectKey = tenantId + "/documents/" + documentId + "/v1/" + originalFileName;

        String storagePath;
        try (InputStream is = file.getInputStream()) {
            storagePath = storageService.upload(objectKey, is, contentType, sizeBytes);
        } catch (IOException e) {
            throw new DocumentValidationException("Failed to read uploaded file: " + e.getMessage());
        }

        try {
            LocalDate issueDate = request.issueDate();
            LocalDate expiryDate = request.expiryDate();
            int reminderDays = request.reminderDays() != null ? request.reminderDays() : 30;
            String category = request.category().trim();
            String docType = (request.docType() != null && !request.docType().isBlank())
                    ? request.docType().trim() : "General";

            // Insert vault_documents
            jdbc.update("""
                    INSERT INTO vault_documents
                      (id, tenant_id, title, doc_type, category, status,
                       issue_date, expiry_date, reminder_days, cert_status,
                       current_version, created_by_id, updated_by_id)
                    VALUES (?, ?, ?, ?, ?, 'active', ?, ?, ?, ?, 1, ?, ?)
                    """,
                    documentId, tenantId, request.title().trim(), docType, category,
                    issueDate, expiryDate, reminderDays, request.certStatus(),
                    userId, userId);

            // Insert vault_document_versions
            jdbc.update("""
                    INSERT INTO vault_document_versions
                      (id, tenant_id, document_id, version_number, storage_path,
                       file_name, content_type, file_size_bytes, issue_date,
                       expiry_date, reminder_days, uploaded_by_id)
                    VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    versionId, tenantId, documentId, storagePath,
                    originalFileName, contentType, sizeBytes,
                    issueDate, expiryDate, reminderDays, userId);

            // Insert vault_document_links if supplied
            if (linkedEntityType != null && !linkedEntityType.isBlank()) {
                String ref = linkedEntityRef != null && !linkedEntityRef.isBlank()
                        ? linkedEntityRef.trim() : (linkedEntityId != null ? linkedEntityId.toString() : "");
                jdbc.update("""
                        INSERT INTO vault_document_links
                          (id, tenant_id, document_id, entity_type, entity_id, entity_ref)
                        VALUES (gen_random_uuid(), ?, ?, ?, ?, ?)
                        """,
                        tenantId, documentId, linkedEntityType.trim(), linkedEntityId, ref);
            }

            // Audit
            auditService.record("VaultDocument", documentId, "DOCUMENT_UPLOADED", null,
                    String.format(Locale.ROOT,
                            "{\"title\":\"%s\",\"category\":\"%s\",\"fileName\":\"%s\",\"version\":1}",
                            escapeJson(request.title().trim()), escapeJson(category), escapeJson(originalFileName)));

            return getDocument(documentId);

        } catch (Exception ex) {
            // Roll back storage on DB failure to avoid orphans
            try {
                storageService.delete(objectKey);
            } catch (Exception cleanupEx) {
                log.warn("Best-effort storage cleanup failed for key: {}", objectKey, cleanupEx);
            }
            throw ex;
        }
    }

    @Transactional
    @PreAuthorize(BossPermissions.DOCUMENTS_MANAGE)
    public DocumentResponse renewDocument(UUID documentId, RenewDocumentRequest request, MultipartFile file) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();
        UUID userId = ctx.userId();

        if (file == null || file.isEmpty()) {
            throw new DocumentValidationException("Please attach the renewed document file");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new DocumentValidationException("File size exceeds maximum permitted limit (10MB)");
        }
        if (request.expiryDate() == null) {
            throw new DocumentValidationException("New expiry date is required");
        }
        if (request.issueDate() != null && request.expiryDate().isBefore(request.issueDate())) {
            throw new DocumentValidationException("Expiry date cannot be earlier than issue date");
        }

        // Fetch current document
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, title, status, current_version, expiry_date, reminder_days
                FROM vault_documents
                WHERE tenant_id = ? AND id = ?
                """, tenantId, documentId);

        if (rows.isEmpty()) {
            throw new DocumentNotFoundException(documentId);
        }

        Map<String, Object> current = rows.get(0);
        String currentStatus = (String) current.get("status");
        if ("archived".equalsIgnoreCase(currentStatus)) {
            throw new DocumentValidationException("Cannot renew an archived document");
        }

        int currentVersion = ((Number) current.get("current_version")).intValue();
        int newVersion = currentVersion + 1;

        String originalFileName = sanitizeFileName(file.getOriginalFilename());
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        long sizeBytes = file.getSize();

        // Storage path for new version
        String objectKey = tenantId + "/documents/" + documentId + "/v" + newVersion + "/" + originalFileName;

        String storagePath;
        try (InputStream is = file.getInputStream()) {
            storagePath = storageService.upload(objectKey, is, contentType, sizeBytes);
        } catch (IOException e) {
            throw new DocumentValidationException("Failed to read renewal file: " + e.getMessage());
        }

        try {
            LocalDate issueDate = request.issueDate() != null ? request.issueDate() : LocalDate.now();
            LocalDate newExpiryDate = request.expiryDate();
            int reminderDays = request.reminderDays() != null ? request.reminderDays()
                    : ((Number) current.getOrDefault("reminder_days", 30)).intValue();
            String notes = request.notes() != null ? request.notes().trim() : null;

            // Update vault_documents current_version and expiry details
            jdbc.update("""
                    UPDATE vault_documents
                    SET current_version = ?,
                        issue_date = ?,
                        expiry_date = ?,
                        reminder_days = ?,
                        status = 'active',
                        updated_by_id = ?,
                        updated_at = NOW()
                    WHERE tenant_id = ? AND id = ?
                    """,
                    newVersion, issueDate, newExpiryDate, reminderDays, userId, tenantId, documentId);

            // Insert new version
            UUID versionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO vault_document_versions
                      (id, tenant_id, document_id, version_number, storage_path,
                       file_name, content_type, file_size_bytes, issue_date,
                       expiry_date, reminder_days, renewal_notes, uploaded_by_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    versionId, tenantId, documentId, newVersion, storagePath,
                    originalFileName, contentType, sizeBytes, issueDate,
                    newExpiryDate, reminderDays, notes, userId);

            // Audit
            auditService.record("VaultDocument", documentId, "DOCUMENT_RENEWED",
                    String.format(Locale.ROOT, "{\"version\":%d,\"expiryDate\":\"%s\"}", currentVersion, current.get("expiry_date")),
                    String.format(Locale.ROOT, "{\"version\":%d,\"expiryDate\":\"%s\",\"fileName\":\"%s\"}",
                            newVersion, newExpiryDate, escapeJson(originalFileName)));

            return getDocument(documentId);

        } catch (Exception ex) {
            try {
                storageService.delete(objectKey);
            } catch (Exception cleanupEx) {
                log.warn("Best-effort storage cleanup failed for key: {}", objectKey, cleanupEx);
            }
            throw ex;
        }
    }

    @Transactional
    @PreAuthorize(BossPermissions.DOCUMENTS_MANAGE)
    public DocumentResponse archiveDocument(UUID documentId, ArchiveDocumentRequest request) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();
        UUID userId = ctx.userId();

        if (request == null || request.reason() == null || request.reason().trim().isBlank()) {
            throw new DocumentValidationException("Archive reason is required");
        }

        String reason = request.reason().trim();

        int updated = jdbc.update("""
                UPDATE vault_documents
                SET status = 'archived',
                    archived_at = NOW(),
                    archived_by_id = ?,
                    archive_reason = ?,
                    updated_by_id = ?,
                    updated_at = NOW()
                WHERE tenant_id = ? AND id = ? AND status != 'archived'
                """,
                userId, reason, userId, tenantId, documentId);

        if (updated == 0) {
            // Check if document exists
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM vault_documents WHERE tenant_id = ? AND id = ?",
                    Integer.class, tenantId, documentId);
            if (exists == null || exists == 0) {
                throw new DocumentNotFoundException(documentId);
            }
            throw new DocumentValidationException("Document is already archived");
        }

        auditService.record("VaultDocument", documentId, "DOCUMENT_ARCHIVED", null,
                String.format(Locale.ROOT, "{\"reason\":\"%s\"}", escapeJson(reason)));

        return getDocument(documentId);
    }

    @Transactional(readOnly = true)
    public StoredDocumentFile downloadDocument(UUID documentId, Integer versionNumber) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        String sql;
        List<Map<String, Object>> rows;

        if (versionNumber != null && versionNumber > 0) {
            sql = """
                    SELECT v.storage_path, v.file_name, v.content_type
                    FROM vault_document_versions v
                    JOIN vault_documents d ON d.id = v.document_id AND d.tenant_id = v.tenant_id
                    WHERE v.tenant_id = ? AND v.document_id = ? AND v.version_number = ?
                    """;
            rows = jdbc.queryForList(sql, tenantId, documentId, versionNumber);
        } else {
            sql = """
                    SELECT v.storage_path, v.file_name, v.content_type
                    FROM vault_documents d
                    JOIN vault_document_versions v ON v.document_id = d.id AND v.version_number = d.current_version AND v.tenant_id = d.tenant_id
                    WHERE d.tenant_id = ? AND d.id = ?
                    """;
            rows = jdbc.queryForList(sql, tenantId, documentId);
        }

        if (rows.isEmpty()) {
            throw new DocumentNotFoundException("Document file not found for id: " + documentId);
        }

        Map<String, Object> row = rows.get(0);
        String storagePath = (String) row.get("storage_path");
        String fileName = (String) row.get("file_name");
        String contentType = (String) row.get("content_type");

        // Object key is extracted from storagePath
        String objectKey = extractObjectKey(storagePath, tenantId, documentId);
        byte[] content = storageService.download(objectKey);

        return new StoredDocumentFile(fileName, contentType, content);
    }

    @Transactional(readOnly = true)
    public List<DocumentHistoryEntry> getDocumentHistory(UUID documentId) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        // Verify document belongs to tenant
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vault_documents WHERE tenant_id = ? AND id = ?",
                Integer.class, tenantId, documentId);
        if (count == null || count == 0) {
            throw new DocumentNotFoundException(documentId);
        }

        return jdbc.query("""
                SELECT a.id, a.action, a.created_at,
                       COALESCE(NULLIF(u.display_name, ''), u.email, 'System') AS actor_name,
                       a.old_data, a.new_data
                FROM audit_events a
                LEFT JOIN app_users u ON u.id = a.actor_id
                WHERE a.tenant_id = ? AND a.entity_type = 'VaultDocument' AND a.entity_id = ?
                ORDER BY a.created_at DESC
                """, (rs, i) -> {
            UUID id = (UUID) rs.getObject("id");
            String action = rs.getString("action");
            Instant ts = rs.getTimestamp("created_at").toInstant();
            String actor = rs.getString("actor_name");
            String description = switch (action) {
                case "DOCUMENT_UPLOADED" -> "Uploaded initial document and file version";
                case "DOCUMENT_RENEWED" -> "Renewed document with new version and expiry";
                case "DOCUMENT_ARCHIVED" -> "Archived document";
                default -> "Document activity: " + action;
            };
            return new DocumentHistoryEntry(id, action, description, actor, ts, Collections.emptyMap());
        }, tenantId, documentId);
    }

    // ---- Private Helpers ----

    private DocumentResponse mapDocumentSummary(ResultSet rs, UUID tenantId) throws SQLException {
        UUID docId = (UUID) rs.getObject("id");
        String title = rs.getString("title");
        String docType = rs.getString("doc_type");
        String category = rs.getString("category");
        String status = rs.getString("status");

        LocalDate issueDate = rs.getDate("issue_date") != null ? rs.getDate("issue_date").toLocalDate() : null;
        LocalDate expiryDate = rs.getDate("expiry_date") != null ? rs.getDate("expiry_date").toLocalDate() : null;
        Integer reminderDays = (Integer) rs.getObject("reminder_days");
        String certStatus = rs.getString("cert_status");
        int version = rs.getInt("current_version");

        Instant archivedAt = rs.getTimestamp("archived_at") != null ? rs.getTimestamp("archived_at").toInstant() : null;
        String archiveReason = rs.getString("archive_reason");

        String uploaderName = rs.getString("uploader_name");
        String uploaderEmail = rs.getString("uploader_email");
        String uploadedBy = (uploaderName != null && !uploaderName.isBlank()) ? uploaderName : (uploaderEmail != null ? uploaderEmail : "System");

        String archiverName = rs.getString("archiver_name");
        String archiverEmail = rs.getString("archiver_email");
        String archivedBy = (archiverName != null && !archiverName.isBlank()) ? archiverName : archiverEmail;

        String fileName = rs.getString("file_name");
        long fileSizeBytes = rs.getLong("file_size_bytes");
        String contentType = rs.getString("content_type");
        String fileSize = formatFileSize(fileSizeBytes);

        Instant uploadedAt = rs.getTimestamp("version_created_at") != null
                ? rs.getTimestamp("version_created_at").toInstant() : Instant.now();

        // Linked entities
        List<LinkedEntityDto> linkedEntities = getLinkedEntities(tenantId, docId);

        return new DocumentResponse(
                docId, title, docType, category, status,
                issueDate, expiryDate, reminderDays, certStatus,
                version, fileName, fileSize, fileSizeBytes, contentType,
                uploadedBy, uploadedAt, archivedAt, archivedBy, archiveReason,
                linkedEntities
        );
    }

    private List<LinkedEntityDto> getLinkedEntities(UUID tenantId, UUID documentId) {
        return jdbc.query("""
                SELECT entity_type, entity_ref, entity_id
                FROM vault_document_links
                WHERE tenant_id = ? AND document_id = ?
                ORDER BY created_at
                """, (rs, i) -> new LinkedEntityDto(
                rs.getString("entity_type"),
                rs.getString("entity_ref"),
                (UUID) rs.getObject("entity_id")
        ), tenantId, documentId);
    }

    private void validateUploadRequest(CreateDocumentRequest request, MultipartFile file) {
        if (request == null) {
            throw new DocumentValidationException("Document metadata is required");
        }
        if (request.title() == null || request.title().trim().isBlank()) {
            throw new DocumentValidationException("Title is required");
        }
        if (request.category() == null || request.category().trim().isBlank()) {
            throw new DocumentValidationException("Category is required");
        }
        if (!ALLOWED_CATEGORIES.contains(request.category().trim())) {
            throw new DocumentValidationException("Invalid category: " + request.category());
        }
        if (file == null || file.isEmpty()) {
            throw new DocumentValidationException("Please select a file to upload");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new DocumentValidationException("File size exceeds maximum permitted limit (10MB)");
        }
        if (request.issueDate() != null && request.expiryDate() != null
                && request.expiryDate().isBefore(request.issueDate())) {
            throw new DocumentValidationException("Expiry date cannot be earlier than issue date");
        }
        if (request.reminderDays() != null && (request.reminderDays() < 1 || request.reminderDays() > 365)) {
            throw new DocumentValidationException("Reminder days must be between 1 and 365");
        }
    }

    private void validateLinkedEntity(UUID tenantId, String type, UUID id) {
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        String salesTable = switch (normalized) {
            case "rfq" -> "rfqs";
            case "quotation" -> "quotations";
            case "customerpo", "customer_po", "customer po", "cpo" -> "cpos";
            default -> null;
        };
        if (salesTable != null) {
            if (id == null) {
                throw new DocumentValidationException("A linked " + type + " ID is required");
            }
            Boolean active = jdbc.query("SELECT c.is_active FROM customers c JOIN " + salesTable
                            + " s ON s.tenant_id = c.tenant_id AND s.customer_id = c.id"
                            + " WHERE s.id = ? AND s.tenant_id = ? FOR UPDATE OF c",
                    rs -> rs.next() ? rs.getBoolean("is_active") : null, id, tenantId);
            if (Boolean.FALSE.equals(active)) {
                throw new DocumentValidationException("New documents cannot be linked to an inactive customer's records");
            }
            String status = jdbc.query("SELECT status FROM " + salesTable
                            + " WHERE id = ? AND tenant_id = ? FOR UPDATE",
                    rs -> rs.next() ? rs.getString("status") : null, id, tenantId);
            if (status == null) {
                throw new DocumentValidationException("Linked " + type + " record not found for this tenant: " + id);
            }
            if ("cancelled".equals(status)) {
                throw new DocumentValidationException("New documents cannot be linked to a cancelled " + type);
            }
            return;
        }
        if ("customer".equals(normalized)) {
            if (id == null) {
                throw new DocumentValidationException("A linked Customer ID is required");
            }
            Boolean active = jdbc.query("SELECT is_active FROM customers WHERE tenant_id = ? AND id = ? FOR UPDATE",
                    rs -> rs.next() ? rs.getBoolean("is_active") : null, tenantId, id);
            if (active == null) {
                throw new DocumentValidationException("Linked Customer record not found for this tenant: " + id);
            }
            if (!active) {
                throw new DocumentValidationException("New documents cannot be linked to an inactive customer");
            }
            return;
        }
        if (id == null) return;
        String table = switch (normalized) {
            case "supplier" -> "suppliers";
            case "customer" -> "customers";
            case "job" -> "production_jobs";
            case "asset" -> "assets";
            default -> null;
        };

        if (table != null) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE id = ? AND tenant_id = ?",
                    Integer.class, id, tenantId);
            if (count == null || count == 0) {
                throw new DocumentValidationException("Linked " + type + " record not found for this tenant: " + id);
            }
        }
    }

    private BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null || context.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant user context is required");
        }
        return context;
    }

    private static String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) {
            return "document.bin";
        }
        String clean = original.replaceAll("[^a-zA-Z0-9._-]", "_");
        return clean.length() > 100 ? clean.substring(clean.length() - 100) : clean;
    }

    private static String extractObjectKey(String storagePath, UUID tenantId, UUID documentId) {
        if (storagePath == null) return "";
        if (storagePath.startsWith("gs://")) {
            int thirdSlash = storagePath.indexOf('/', 5);
            if (thirdSlash > 0) {
                return storagePath.substring(thirdSlash + 1);
            }
        } else if (storagePath.startsWith("mem://")) {
            return storagePath.substring(6);
        }
        return storagePath;
    }

    public static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 KB";
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record StoredDocumentFile(String fileName, String contentType, byte[] content) {}
}
