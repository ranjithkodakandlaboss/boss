package in.boss.documents.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DocumentDtos {

    private DocumentDtos() {}

    public record CreateDocumentRequest(
            @NotBlank(message = "Title is required")
            String title,

            @NotBlank(message = "Category is required")
            String category,

            String docType,

            LocalDate issueDate,

            LocalDate expiryDate,

            @Min(value = 1, message = "Reminder days must be at least 1")
            @Max(value = 365, message = "Reminder days cannot exceed 365")
            Integer reminderDays,

            String certStatus,

            String linkedEntityType,

            UUID linkedEntityId,

            String linkedEntityRef
    ) {}

    public record RenewDocumentRequest(
            LocalDate issueDate,

            @NotNull(message = "New expiry date is required")
            LocalDate expiryDate,

            @Min(value = 1, message = "Reminder days must be at least 1")
            @Max(value = 365, message = "Reminder days cannot exceed 365")
            Integer reminderDays,

            String notes
    ) {}

    public record ArchiveDocumentRequest(
            @NotBlank(message = "Archive reason is required")
            @Size(min = 1, max = 1000, message = "Archive reason must be between 1 and 1000 characters")
            String reason
    ) {}

    public record LinkedEntityDto(
            String type,
            String ref,
            UUID id
    ) {}

    public record DocumentResponse(
            UUID id,
            String title,
            String docType,
            String category,
            String status,
            LocalDate issueDate,
            LocalDate expiryDate,
            Integer reminderDays,
            String certStatus,
            int version,
            String fileName,
            String fileSize,
            long fileSizeBytes,
            String contentType,
            String uploadedBy,
            Instant uploadedAt,
            Instant archivedAt,
            String archivedBy,
            String archiveReason,
            List<LinkedEntityDto> linkedEntities
    ) {}

    public record DocumentHistoryEntry(
            UUID id,
            String action,
            String description,
            String actor,
            Instant timestamp,
            Map<String, Object> details
    ) {}

    public record PageResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements
    ) {}
}
