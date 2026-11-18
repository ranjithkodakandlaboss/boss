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
            Instant archivedAt, int openEvents, Instant createdAt, List<AssetCapability> capabilities
    ) {}

    public record AssetCapability(UUID stageOptionId, String stageLabel) {}

    public record CapabilityRequest(List<UUID> stageOptionIds) {}

    public record EventRequest(
            UUID assetId, String eventType, String severity, String symptom, UUID technicianUserId,
            String technicianName, LocalDate plannedDate, BigDecimal expectedDurationHours,
            Instant downtimeStart, Instant downtimeEnd, Instant completionDate,
            List<UUID> affectedJobIds, String correctiveAction, String notes, String rootCause,
            String workDone, String resolutionNotes, String partsUsed
    ) {
        public EventRequest(UUID assetId, String eventType, String severity, String symptom, UUID technicianUserId,
                            String technicianName, LocalDate plannedDate, BigDecimal expectedDurationHours,
                            Instant downtimeStart, Instant downtimeEnd, Instant completionDate,
                            List<UUID> affectedJobIds, String correctiveAction) {
            this(assetId, eventType, severity, symptom, technicianUserId, technicianName, plannedDate,
                    expectedDurationHours, downtimeStart, downtimeEnd, completionDate, affectedJobIds,
                    correctiveAction, null, null, null, null, null);
        }
    }

    public record CloseEventRequest(
            Instant completionDate, String rootCause, String workDone, String resolutionNotes,
            String correctiveAction, String partsUsed
    ) {}

    public record RepairRequest(Instant completionDate, String rootCause, String workDone, String resolutionNotes) {}

    public record ActivityEntry(String action, String actor, Instant at) {}

    public record AffectedJob(UUID id, String jobNumber) {}

    public record AlternativeAsset(UUID id, String assetCode, String name, String workCentre) {}

    public record AffectedStage(
            UUID id, UUID jobId, String jobNumber, int sequence, String name, String status,
            String customerOrderNumber, LocalDate requiredByDate, List<AlternativeAsset> alternatives
    ) {}

    public record EventResponse(
            UUID id, String eventNumber, UUID assetId, String assetCode, String assetName, String eventType,
            String status, String severity, String symptom, UUID technicianUserId, String technicianName,
            LocalDate plannedDate, BigDecimal expectedDurationHours, Instant downtimeStart,
            Instant downtimeEnd, Instant completionDate, BigDecimal downtimeHours,
            List<AffectedJob> affectedJobs, List<AffectedStage> affectedStages, UUID responsibleUserId,
            String responsibleOwner, String rootCause, String correctiveAction, String workDone,
            String resolutionNotes, String partsUsed, Instant createdAt, UUID siteId, String notes
    ) {}

    public record SiteOption(UUID id, String name, String code) {}

    public record TechnicianOption(UUID id, String name) {}

    public record JobOption(UUID id, String jobNumber, String status) {}

    public record Lookups(List<SiteOption> sites, List<TechnicianOption> technicians, List<JobOption> jobs) {}
}
