package in.boss.maintenance.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class MaintenanceDtos {

    private MaintenanceDtos() {}

    public record AssetRequest(
            String assetCode, String name, String category, UUID siteId, String workCentre,
            String manufacturer, String model, String serialNumber, String criticality,
            String meterType, BigDecimal currentReading, BigDecimal lastServiceReading,
            LocalDate lastServiceDate, LocalDate nextServiceDate, String pmFrequencyMetric,
            BigDecimal pmFrequencyHours, Integer pmFrequencyDays, UUID responsibleUserId,
            String responsiblePerson, LocalDate installDate, String status, String readingNotes
    ) {}

    public record ArchiveRequest(String reason) {}

    public record MeterReadingRequest(BigDecimal reading, LocalDate readingDate, String readingType, String notes) {}

    public record MeterReadingResponse(
            UUID id, UUID assetId, BigDecimal reading, BigDecimal previousReading, LocalDate readingDate,
            String readingType, String recordedBy, String notes, Instant createdAt
    ) {}

    public record AssetResponse(
            UUID id, String assetCode, String name, String category, UUID siteId, String siteName,
            String workCentre, String manufacturer, String model, String serialNumber, String criticality,
            String meterType, BigDecimal currentReading, BigDecimal lastServiceReading,
            LocalDate lastServiceDate, LocalDate nextServiceDate, String pmFrequencyMetric,
            BigDecimal pmFrequencyHours, Integer pmFrequencyDays, UUID responsibleUserId,
            String responsiblePerson, LocalDate installDate, String status, String archiveReason,
            Instant archivedAt, int openEvents, Instant createdAt
    ) {}

    public record EventRequest(
            UUID assetId, String eventType, String severity, String symptom, UUID technicianUserId,
            String technicianName, LocalDate plannedDate, BigDecimal expectedDurationHours,
            Instant downtimeStart, Instant downtimeEnd, Instant completionDate,
            List<UUID> affectedJobIds, String correctiveAction
    ) {}

    public record CloseEventRequest(
            Instant completionDate, String rootCause, String workDone, String resolutionNotes,
            String correctiveAction, String partsUsed
    ) {}

    public record RepairRequest(String rootCause, String workDone, String resolutionNotes) {}

    public record AffectedJob(UUID id, String jobNumber) {}

    public record EventResponse(
            UUID id, String eventNumber, UUID assetId, String assetCode, String assetName, String eventType,
            String status, String severity, String symptom, UUID technicianUserId, String technicianName,
            LocalDate plannedDate, BigDecimal expectedDurationHours, Instant downtimeStart,
            Instant downtimeEnd, Instant completionDate, BigDecimal downtimeHours,
            List<AffectedJob> affectedJobs, String rootCause, String correctiveAction, String workDone,
            String resolutionNotes, String partsUsed, Instant createdAt
    ) {}

    public record SiteOption(UUID id, String name, String code) {}

    public record TechnicianOption(UUID id, String name) {}

    public record JobOption(UUID id, String jobNumber, String status) {}

    public record Lookups(List<SiteOption> sites, List<TechnicianOption> technicians, List<JobOption> jobs) {}
}
