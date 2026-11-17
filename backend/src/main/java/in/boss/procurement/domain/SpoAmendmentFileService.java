package in.boss.procurement.domain;

import in.boss.procurement.api.ProcurementApiDtos.AmendmentFileResponse;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import in.boss.shared.security.BossPermissions;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@PreAuthorize(BossPermissions.PROCUREMENT_MANAGE)
public class SpoAmendmentFileService {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("pdf", "jpg", "jpeg", "png", "msg", "eml");

    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    public SpoAmendmentFileService(JdbcTemplate jdbc, AuditService auditService) {
        this.jdbc = jdbc;
        this.auditService = auditService;
    }

    @Transactional
    public AmendmentFileResponse upload(UUID spoId, UUID amendmentId, MultipartFile file) {
        BossContext context = requireContext();
        requireAmendment(context.tenantId(), spoId, amendmentId);
        if (file == null || file.isEmpty()) {
            throw badRequest("Choose the amendment evidence file");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim();
        String extension = extension(name);
        if (!EXTENSIONS.contains(extension)) {
            throw badRequest("Amendment evidence must be a PDF, JPEG, PNG, MSG, or EML file");
        }
        if (file.getSize() > MAX_BYTES) {
            throw badRequest("Amendment evidence must be 5 MB or smaller");
        }
        String contentType = contentType(extension, file.getContentType());
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw badRequest("The evidence file could not be read");
        }
        if (content.length == 0 || content.length > MAX_BYTES) {
            throw badRequest("Amendment evidence must be 5 MB or smaller");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO spo_amendment_files
                  (id, tenant_id, amendment_id, file_name, content_type, size_bytes, content, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, context.tenantId(), amendmentId, name, contentType, content.length, content, context.userId());
        auditService.record("spo_amendment_files", id, "CREATE", null,
                "{\"amendmentId\":\"" + amendmentId + "\",\"fileName\":\"" + name.replace("\"", "") + "\"}");
        return new AmendmentFileResponse(id, amendmentId, name, contentType, content.length);
    }

    @Transactional(readOnly = true)
    public List<AmendmentFileResponse> list(UUID spoId, UUID amendmentId) {
        BossContext context = requireContext();
        requireAmendment(context.tenantId(), spoId, amendmentId);
        return jdbc.query("""
                SELECT id, amendment_id, file_name, content_type, size_bytes
                FROM spo_amendment_files
                WHERE tenant_id = ? AND amendment_id = ?
                ORDER BY created_at
                """, (rs, rowNum) -> new AmendmentFileResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("amendment_id", UUID.class),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getInt("size_bytes")), context.tenantId(), amendmentId);
    }

    @Transactional(readOnly = true)
    public StoredFile download(UUID spoId, UUID amendmentId, UUID fileId) {
        BossContext context = requireContext();
        requireAmendment(context.tenantId(), spoId, amendmentId);
        List<StoredFile> rows = jdbc.query("""
                SELECT file_name, content_type, content
                FROM spo_amendment_files
                WHERE tenant_id = ? AND amendment_id = ? AND id = ?
                """, (rs, rowNum) -> new StoredFile(
                rs.getString("file_name"), rs.getString("content_type"), rs.getBytes("content")),
                context.tenantId(), amendmentId, fileId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Amendment evidence not found");
        }
        return rows.get(0);
    }

    private void requireAmendment(UUID tenantId, UUID spoId, UUID amendmentId) {
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM spo_amendments
                WHERE tenant_id = ? AND spo_id = ? AND id = ?
                """, Integer.class, tenantId, spoId, amendmentId);
        if (found == null || found == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Amendment not found");
        }
    }

    private BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null || context.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant user context is required");
        }
        return context;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String contentType(String extension, String supplied) {
        String normalized = supplied == null ? "" : supplied.toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "pdf" -> "application/pdf".equals(normalized) ? normalized : rejectType();
            case "jpg", "jpeg" -> "image/jpeg".equals(normalized) ? normalized : rejectType();
            case "png" -> "image/png".equals(normalized) ? normalized : rejectType();
            case "msg" -> allowed(normalized, "application/vnd.ms-outlook", "application/octet-stream");
            case "eml" -> allowed(normalized, "message/rfc822", "application/octet-stream");
            default -> rejectType();
        };
    }

    private static String allowed(String supplied, String... accepted) {
        for (String type : accepted) {
            if (type.equals(supplied)) {
                return supplied;
            }
        }
        return rejectType();
    }

    private static String rejectType() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "The file type does not match an allowed amendment evidence type");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record StoredFile(String fileName, String contentType, byte[] content) {}
}
