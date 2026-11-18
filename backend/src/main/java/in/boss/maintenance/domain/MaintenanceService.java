package in.boss.maintenance.domain;

import in.boss.maintenance.api.MaintenanceDtos.*;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static in.boss.shared.security.BossPermissions.MAINTENANCE_VIEW;
import static in.boss.shared.security.BossPermissions.MAINTENANCE_WRITE;

@Service
@Transactional
public class MaintenanceService {

    private static final String ASSET_SELECT = """
            SELECT a.*, s.name AS site_name,
                   (SELECT count(*) FROM maintenance_events e
                     WHERE e.tenant_id = a.tenant_id AND e.asset_id = a.id AND e.status <> 'closed') AS open_events
            FROM assets a JOIN physical_sites s ON s.id = a.site_id
            """;

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public MaintenanceService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---- lookups ---------------------------------------------------------

    @PreAuthorize(MAINTENANCE_VIEW)
    @Transactional(readOnly = true)
    public Lookups lookups() {
        BossContext ctx = context();
        List<SiteOption> sites = jdbc.query("""
                SELECT id, name, code FROM physical_sites
                WHERE tenant_id = ? AND status = 'active' ORDER BY name
                """, (rs, i) -> new SiteOption(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("code")), ctx.tenantId()).stream().filter(s -> ctx.canAccessSite(s.id())).toList();
        List<TechnicianOption> technicians = jdbc.query("""
                SELECT id, COALESCE(NULLIF(display_name, ''), email) AS name FROM app_users
                WHERE tenant_id = ? AND status = 'active' ORDER BY 2
                """, (rs, i) -> new TechnicianOption(rs.getObject("id", UUID.class), rs.getString("name")),
                ctx.tenantId());
        List<JobOption> jobs = jdbc.query("""
                SELECT id, job_number, status FROM production_jobs
                WHERE tenant_id = ? AND status IN ('planned','released','in_progress','on_hold')
                  AND (?::uuid IS NULL OR site_id = ?::uuid)
                ORDER BY job_number
                """, (rs, i) -> new JobOption(rs.getObject("id", UUID.class), rs.getString("job_number"),
                rs.getString("status")), ctx.tenantId(), ctx.siteId(), ctx.siteId());
        return new Lookups(sites, technicians, jobs);
    }

    // ---- assets ----------------------------------------------------------

    @PreAuthorize(MAINTENANCE_VIEW)
    @Transactional(readOnly = true)
    public List<AssetResponse> listAssets(boolean includeArchived) {
        BossContext ctx = context();
        String filter = includeArchived ? "" : " AND a.status <> 'retired'";
        return jdbc.query(ASSET_SELECT + " WHERE a.tenant_id = ?" + filter + " ORDER BY a.asset_number",
                assetMapper(), ctx.tenantId()).stream().filter(a -> ctx.canAccessSite(a.siteId())).toList();
    }

    @PreAuthorize(MAINTENANCE_VIEW)
    @Transactional(readOnly = true)
    public AssetResponse getAsset(UUID id) {
        return loadAsset(id);
    }

    @PreAuthorize(MAINTENANCE_WRITE)
    public AssetResponse createAsset(AssetRequest r) {
        BossContext ctx = context();
        String code = required(r.assetCode(), "Asset code");
        String name = required(r.name(), "Asset name");
        required(r.manufacturer(), "Manufacturer");
        if (r.siteId() == null) throw bad("Site is required");
        requireSite(ctx, r.siteId());
        String category = option("ASSET_CATEGORY", r.category(), true);
        String workCentre = r.workCentre() == null || r.workCentre().isBlank()
                ? null : option("WORK_CENTRE", r.workCentre(), true);
        String criticality = option("ASSET_CRITICALITY", r.criticality() == null ? "Medium" : r.criticality(), false);
        String meter = option("METER_TYPE", r.meterType() == null ? "none" : r.meterType(), false);
        validatePm(r, meter);
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM assets WHERE tenant_id = ? AND lower(asset_number) = lower(?)",
                Integer.class, ctx.tenantId(), code.trim());
        if (exists != null && exists > 0) throw conflict("Asset code " + code.trim() + " already exists");
        String responsible = responsibleName(ctx, r.responsibleUserId(), r.responsiblePerson());
        UUID id = UUID.randomUUID();
        BigDecimal reading = "none".equals(meter) ? null : r.currentReading();
        jdbc.update("""
                INSERT INTO assets (id, tenant_id, asset_number, name, asset_type, category, site_id, work_centre,
                  make, model, serial_number, criticality, meter_type, current_reading, last_service_reading,
                  last_service_date, next_service_date, pm_frequency_metric, pm_frequency_hours, pm_frequency_days,
                  responsible_user_id, responsible_person, install_date, status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'operational')
                """, id, ctx.tenantId(), code.trim(), name.trim(), category, category, r.siteId(), workCentre,
                r.manufacturer().trim(), r.model(), r.serialNumber(), criticality, meter, reading,
                r.lastServiceReading(), r.lastServiceDate(), r.nextServiceDate(), r.pmFrequencyMetric(),
                r.pmFrequencyHours(), r.pmFrequencyDays(), r.responsibleUserId(), responsible, r.installDate());
        if (reading != null) {
            insertReading(ctx, id, reading, null, LocalDate.now(), "routine", "Initial reading");
        }
        audit.record("asset", id, "CREATE", null, "{\"assetCode\":\"" + code.trim() + "\"}");
        return loadAsset(id);
    }

    @PreAuthorize(MAINTENANCE_WRITE)
    public AssetResponse updateAsset(UUID id, AssetRequest r) {
        BossContext ctx = context();
        AssetResponse current = loadAsset(id);
        if ("retired".equals(current.status())) throw conflict("Retired assets cannot be edited");
        String name = r.name() == null ? current.name() : required(r.name(), "Asset name");
        String manufacturer = r.manufacturer() == null ? current.manufacturer() : required(r.manufacturer(), "Manufacturer");
        UUID siteId = r.siteId() == null ? current.siteId() : r.siteId();
        requireSite(ctx, siteId);
        String category = r.category() == null ? current.category() : option("ASSET_CATEGORY", r.category(), true);
        String workCentre = r.workCentre() == null ? current.workCentre() : option("WORK_CENTRE", r.workCentre(), true);
        String criticality = r.criticality() == null ? current.criticality() : option("ASSET_CRITICALITY", r.criticality(), false);
        String meter = r.meterType() == null ? current.meterType() : option("METER_TYPE", r.meterType(), false);
        validatePm(r, meter);
        String status = current.status();
        if (r.status() != null && !r.status().equals(status)) {
            status = option("ASSET_STATUS", r.status(), false);
            if ("retired".equals(status)) throw bad("Use Archive to retire an asset");
            if ("operational".equals(status)) {
                String blocker = openBreakdownNumber(id);
                if (blocker != null) {
                    throw conflict("Cannot mark " + current.name() + " operational while breakdown " + blocker
                            + " is unresolved.");
                }
            }
        }
        BigDecimal reading = "none".equals(meter) ? null
                : (r.currentReading() != null ? r.currentReading() : current.currentReading());
        if (reading != null && (current.currentReading() == null || reading.compareTo(current.currentReading()) != 0)) {
            String type = current.currentReading() != null && reading.compareTo(current.currentReading()) < 0
                    ? "correction" : "routine";
            insertReading(ctx, id, reading, current.currentReading(), LocalDate.now(), type, r.readingNotes());
        }
        String responsible = r.responsibleUserId() != null || r.responsiblePerson() != null
                ? responsibleName(ctx, r.responsibleUserId(), r.responsiblePerson()) : current.responsiblePerson();
        UUID responsibleId = r.responsibleUserId() != null ? r.responsibleUserId() : current.responsibleUserId();
        jdbc.update("""
                UPDATE assets SET name = ?, asset_type = ?, category = ?, site_id = ?, work_centre = ?, make = ?,
                  model = ?, serial_number = ?, criticality = ?, meter_type = ?, current_reading = ?,
                  last_service_reading = ?, last_service_date = ?, next_service_date = ?, pm_frequency_metric = ?,
                  pm_frequency_hours = ?, pm_frequency_days = ?, responsible_user_id = ?, responsible_person = ?,
                  install_date = ?, status = ?, updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """, name.trim(), category, category, siteId, workCentre, manufacturer.trim(),
                or(r.model(), current.model()), or(r.serialNumber(), current.serialNumber()), criticality, meter,
                reading, or(r.lastServiceReading(), current.lastServiceReading()),
                or(r.lastServiceDate(), current.lastServiceDate()), or(r.nextServiceDate(), current.nextServiceDate()),
                or(r.pmFrequencyMetric(), current.pmFrequencyMetric()),
                or(r.pmFrequencyHours(), current.pmFrequencyHours()), or(r.pmFrequencyDays(), current.pmFrequencyDays()),
                responsibleId, responsible, or(r.installDate(), current.installDate()), status,
                ctx.tenantId(), id);
        audit.record("asset", id, "UPDATE", "{\"status\":\"" + current.status() + "\"}",
                "{\"status\":\"" + status + "\"}");
        return loadAsset(id);
    }

    @PreAuthorize(MAINTENANCE_WRITE)
    public AssetResponse archiveAsset(UUID id, ArchiveRequest r) {
        BossContext ctx = context();
        AssetResponse asset = loadAsset(id);
        String reason = required(r == null ? null : r.reason(), "Archive reason");
        if ("retired".equals(asset.status())) throw conflict("Asset is already archived");
        if (asset.openEvents() > 0) {
            throw conflict("Cannot archive " + asset.assetCode() + " while maintenance events are still open");
        }
        jdbc.update("""
                UPDATE assets SET status = 'retired', archive_reason = ?, archived_at = NOW(), archived_by = ?,
                  updated_at = NOW() WHERE tenant_id = ? AND id = ?
                """, reason.trim(), ctx.userId(), ctx.tenantId(), id);
        audit.record("asset", id, "ARCHIVE", "{\"status\":\"" + asset.status() + "\"}", "{\"status\":\"retired\"}");
        return loadAsset(id);
    }

    // ---- meter readings --------------------------------------------------

    @PreAuthorize(MAINTENANCE_VIEW)
    @Transactional(readOnly = true)
    public List<MeterReadingResponse> readings(UUID assetId) {
        loadAsset(assetId);
        return jdbc.query("""
                SELECT r.*, COALESCE(NULLIF(u.display_name,''), u.email) AS recorder
                FROM asset_meter_readings r LEFT JOIN app_users u ON u.id = r.recorded_by
                WHERE r.tenant_id = ? AND r.asset_id = ? ORDER BY r.created_at DESC
                """, (rs, i) -> new MeterReadingResponse(rs.getObject("id", UUID.class),
                rs.getObject("asset_id", UUID.class), rs.getBigDecimal("reading"),
                rs.getBigDecimal("previous_reading"), rs.getDate("reading_date").toLocalDate(),
                rs.getString("reading_type"), rs.getString("recorder"), rs.getString("notes"),
                rs.getTimestamp("created_at").toInstant()), context().tenantId(), assetId);
    }

    @PreAuthorize(MAINTENANCE_WRITE)
    public AssetResponse logReading(UUID assetId, MeterReadingRequest r) {
        BossContext ctx = context();
        AssetResponse asset = loadAsset(assetId);
        if ("retired".equals(asset.status())) throw conflict("Retired assets cannot take readings");
        if ("none".equals(asset.meterType())) throw bad("This asset is tracked by calendar only and has no meter");
        if (r == null || r.reading() == null || r.reading().signum() < 0) throw bad("A non-negative reading is required");
        String type = r.readingType() == null ? "routine" : r.readingType();
        if (!List.of("routine", "shift-end", "post-maintenance", "correction").contains(type)) {
            throw bad("Unknown reading type");
        }
        if (asset.currentReading() != null && r.reading().compareTo(asset.currentReading()) < 0
                && !"correction".equals(type)) {
            throw bad("Reading cannot be lower than the current reading " + asset.currentReading().stripTrailingZeros()
                    .toPlainString() + " unless it is a correction");
        }
        LocalDate date = r.readingDate() == null ? LocalDate.now() : r.readingDate();
        if (date.isAfter(LocalDate.now())) throw bad("Reading date cannot be in the future");
        insertReading(ctx, assetId, r.reading(), asset.currentReading(), date, type, r.notes());
        jdbc.update("UPDATE assets SET current_reading = ?, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                r.reading(), ctx.tenantId(), assetId);
        audit.record("asset", assetId, "METER_READING", null, "{\"reading\":" + r.reading() + "}");
        return loadAsset(assetId);
    }

    // ---- events ----------------------------------------------------------

    @PreAuthorize(MAINTENANCE_VIEW)
    @Transactional(readOnly = true)
    public List<EventResponse> listEvents(UUID assetId, UUID jobId) {
        BossContext ctx = context();
        StringBuilder sql = new StringBuilder(EVENT_SELECT + " WHERE e.tenant_id = ?");
        List<Object> args = new ArrayList<>(List.of(ctx.tenantId()));
        if (assetId != null) { sql.append(" AND e.asset_id = ?"); args.add(assetId); }
        if (jobId != null) { sql.append(" AND ? = ANY(e.affected_job_ids)"); args.add(jobId); }
        sql.append(" ORDER BY e.created_at DESC");
        return jdbc.query(sql.toString(), eventMapper(), args.toArray()).stream()
                .filter(e -> ctx.canAccessSite(e.siteId())).toList();
    }

    @PreAuthorize(MAINTENANCE_VIEW)
    @Transactional(readOnly = true)
    public EventResponse getEvent(UUID id) {
        return loadEvent(id);
    }

    @PreAuthorize(MAINTENANCE_WRITE)
    public EventResponse createEvent(EventRequest r) {
        BossContext ctx = context();
        if (r.assetId() == null) throw bad("Asset is required");
        AssetResponse asset = loadAsset(r.assetId());
        if ("retired".equals(asset.status())) throw conflict("Cannot log events against retired asset " + asset.assetCode());
        String type = option("MAINTENANCE_EVENT_TYPE", r.eventType(), false);
        String symptom = required(r.symptom(), "Symptom / scope of work");
        String severity = r.severity() == null || r.severity().isBlank() ? null
                : option("MAINTENANCE_SEVERITY", r.severity(), false);
        String technician = technicianName(ctx, r.technicianUserId(), r.technicianName());
        if (technician == null) throw bad("Technician / responsible person is required");
        List<UUID> jobs = r.affectedJobIds() == null ? List.of() : r.affectedJobIds();
        if ("Breakdown".equals(type)) {
            if (r.downtimeStart() == null) throw bad("Downtime start is required for a breakdown");
            if (severity == null) throw bad("Severity is required for a breakdown");
            if (r.downtimeEnd() != null && r.downtimeEnd().isBefore(r.downtimeStart())) {
                throw bad("Downtime end cannot be earlier than downtime start");
            }
            if (r.downtimeStart().isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
                throw bad("Downtime start cannot be in the future");
            }
        } else {
            if (r.plannedDate() == null) throw bad("Planned date is required");
            if ("Corrective".equals(type) && severity == null) throw bad("Severity is required for a corrective event");
            if (!jobs.isEmpty()) throw bad("Affected production jobs apply to breakdown events only");
        }
        if (r.completionDate() != null && r.downtimeStart() != null && r.completionDate().isBefore(r.downtimeStart())) {
            throw bad("Completion cannot be earlier than downtime start");
        }
        validateJobs(ctx, jobs);
        UUID id = UUID.randomUUID();
        String number = nextEventNumber(ctx.tenantId());
        String status = "Preventive".equals(type) ? "scheduled" : "open";
        jdbc.update("""
                INSERT INTO maintenance_events (id, tenant_id, event_number, asset_id, event_type, status, severity,
                  symptom, technician_user_id, technician_name, planned_date, expected_duration_hours,
                  downtime_start, downtime_end, affected_job_ids, corrective_action, notes, created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::uuid[],?,?,?)
                """, id, ctx.tenantId(), number, asset.id(), type, status, severity, symptom.trim(),
                r.technicianUserId(), technician, r.plannedDate(), r.expectedDurationHours(),
                ts(r.downtimeStart()), ts(r.downtimeEnd()), uuidArray(jobs), r.correctiveAction(), blankToNull(r.notes()), ctx.userId());
        audit.record("maintenance_event", id, "CREATE", null, "{\"eventNumber\":\"" + number + "\"}");
        if ("Breakdown".equals(type)) {
            jdbc.update("UPDATE assets SET status = 'breakdown', updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                    ctx.tenantId(), asset.id());
        }
        if (r.completionDate() != null) {
            closeInternal(ctx, id, new CloseEventRequest(r.completionDate(), r.rootCause(), r.workDone(),
                    r.resolutionNotes(), r.correctiveAction(), r.partsUsed()));
        }
        return loadEvent(id);
    }

    @PreAuthorize(MAINTENANCE_WRITE)
    public EventResponse startEvent(UUID id) {
        BossContext ctx = context();
        EventResponse e = loadEvent(id);
        if (!List.of("open", "scheduled").contains(e.status())) {
            throw conflict("Only open or scheduled events can be started");
        }
        jdbc.update("UPDATE maintenance_events SET status = 'in_progress', updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                ctx.tenantId(), id);
        if (!"Breakdown".equals(e.eventType())) {
            jdbc.update("""
                    UPDATE assets SET status = 'in_maintenance', updated_at = NOW()
                    WHERE tenant_id = ? AND id = ? AND status IN ('operational','pm_overdue')
                    """, ctx.tenantId(), e.assetId());
        }
        audit.record("maintenance_event", id, "START", null, "{\"status\":\"in_progress\"}");
        return loadEvent(id);
    }

    @PreAuthorize(MAINTENANCE_WRITE)
    public EventResponse closeEvent(UUID id, CloseEventRequest r) {
        return closeInternal(context(), id, r);
    }

    @PreAuthorize(MAINTENANCE_WRITE)
    public EventResponse markRepaired(UUID assetId, RepairRequest r) {
        BossContext ctx = context();
        loadAsset(assetId);
        if (r == null || r.completionDate() == null) throw bad("Completion time is required");
        if (r.completionDate().isAfter(Instant.now().plus(Duration.ofMinutes(5)))) throw bad("Completion cannot be in the future");
        required(r.rootCause(), "Root cause / diagnosis");
        required(r.workDone(), "Work done");
        List<UUID> open = jdbc.query("""
                SELECT id FROM maintenance_events
                WHERE tenant_id = ? AND asset_id = ? AND event_type = 'Breakdown' AND status <> 'closed'
                ORDER BY created_at DESC
                """, (rs, i) -> rs.getObject("id", UUID.class), ctx.tenantId(), assetId);
        if (open.isEmpty()) throw conflict("This asset has no open breakdown to repair");
        return closeInternal(ctx, open.get(0), new CloseEventRequest(r.completionDate(),
                r.rootCause(), r.workDone(), r.resolutionNotes(), null, null));
    }

    private EventResponse closeInternal(BossContext ctx, UUID id, CloseEventRequest r) {
        EventResponse e = loadEvent(id);
        if ("closed".equals(e.status())) throw conflict("Event " + e.eventNumber() + " is already closed");
        boolean noWork = (r.workDone() == null || r.workDone().isBlank())
                && (r.resolutionNotes() == null || r.resolutionNotes().isBlank());
        if (noWork) throw bad("Work done or resolution notes are required to close the event");
        Instant completion = r.completionDate() == null ? Instant.now() : r.completionDate();
        if (e.downtimeStart() != null && completion.isBefore(e.downtimeStart())) {
            throw bad("Completion cannot be earlier than downtime start");
        }
        BigDecimal downtime = null;
        Instant downtimeFrom = e.downtimeStart();
        if (downtimeFrom == null && e.plannedDate() != null) {
            downtimeFrom = e.plannedDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        if (downtimeFrom != null && !completion.isBefore(downtimeFrom)) {
            downtime = BigDecimal.valueOf(Duration.between(downtimeFrom, completion).toMinutes())
                    .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        }
        jdbc.update("""
                UPDATE maintenance_events SET status = 'closed', completion_date = ?, downtime_end = COALESCE(downtime_end, ?),
                  downtime_hours = ?, root_cause = ?, work_done = ?, resolution_notes = ?,
                  corrective_action = COALESCE(?, corrective_action), parts_used = ?, closed_by = ?, updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """, ts(completion), "Breakdown".equals(e.eventType()) ? ts(completion) : null, downtime,
                r.rootCause(), r.workDone(), r.resolutionNotes(), r.correctiveAction(), r.partsUsed(),
                ctx.userId(), ctx.tenantId(), id);
        AssetResponse asset = loadAsset(e.assetId());
        LocalDate completed = completion.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate next = asset.nextServiceDate();
        if (next == null && asset.pmFrequencyDays() != null && asset.pmFrequencyDays() > 0) {
            next = completed.plusDays(asset.pmFrequencyDays());
        }
        jdbc.update("""
                UPDATE assets SET last_service_date = ?, last_service_reading = COALESCE(current_reading, last_service_reading),
                  next_service_date = ?, status = ?, updated_at = NOW() WHERE tenant_id = ? AND id = ?
                """, completed, next, restoredStatus(ctx.tenantId(), e.assetId(), id, asset.status()),
                ctx.tenantId(), e.assetId());
        audit.record("maintenance_event", id, "CLOSE", "{\"status\":\"" + e.status() + "\"}", "{\"status\":\"closed\"}");
        return loadEvent(id);
    }

    private String restoredStatus(UUID tenantId, UUID assetId, UUID closingEventId, String currentStatus) {
        if ("retired".equals(currentStatus)) return currentStatus;
        List<String> remaining = jdbc.query("""
                SELECT event_type || ':' || status FROM maintenance_events
                WHERE tenant_id = ? AND asset_id = ? AND id <> ? AND status <> 'closed'
                """, (rs, i) -> rs.getString(1), tenantId, assetId, closingEventId);
        if (remaining.stream().anyMatch(s -> s.startsWith("Breakdown:"))) return "breakdown";
        if (remaining.stream().anyMatch(s -> s.endsWith(":in_progress"))) return "in_maintenance";
        return "operational";
    }

    // ---- production integration -----------------------------------------

    /** Open breakdowns that list this job as affected, for the stage's work centre (or any, when none given). */
    @Transactional(readOnly = true)
    public String machineBlocker(UUID tenantId, UUID jobId, String workCentre) {
        return machineBlocker(tenantId, jobId, null, workCentre);
    }

    /**
     * Blocks a stage assigned to an unavailable asset, and otherwise keeps the existing
     * affected-job work-centre check for stages that have no asset assignment.
     */
    @Transactional(readOnly = true)
    public String machineBlocker(UUID tenantId, UUID jobId, UUID stageId, String workCentre) {
        if (stageId != null) {
            List<UUID> linked = jdbc.query("""
                    SELECT asset_id FROM job_stages
                    WHERE tenant_id = ? AND id = ? AND asset_id IS NOT NULL
                    """, (rs, i) -> rs.getObject(1, UUID.class), tenantId, stageId);
            if (!linked.isEmpty()) {
                List<String> direct = jdbc.query("""
                        SELECT COALESCE(e.event_number, 'maintenance') || ' on ' || a.asset_number || ' (' || a.name || ')'
                        FROM assets a
                        LEFT JOIN LATERAL (
                          SELECT event_number FROM maintenance_events
                          WHERE tenant_id = a.tenant_id AND asset_id = a.id AND status <> 'closed'
                            AND event_type = 'Breakdown'
                          ORDER BY created_at LIMIT 1
                        ) e ON TRUE
                        WHERE a.tenant_id = ? AND a.id = ?
                          AND (a.status IN ('breakdown', 'in_maintenance') OR e.event_number IS NOT NULL)
                        LIMIT 1
                        """, (rs, i) -> rs.getString(1), tenantId, linked.get(0));
                return direct.isEmpty() ? null : direct.get(0);
            }
        }
        List<String> blockers = jdbc.query("""
                SELECT e.event_number || ' on ' || a.asset_number
                FROM maintenance_events e JOIN assets a ON a.id = e.asset_id AND a.tenant_id = e.tenant_id
                WHERE e.tenant_id = ? AND e.event_type = 'Breakdown' AND e.status <> 'closed'
                  AND ? = ANY(e.affected_job_ids)
                  AND (?::text IS NULL OR a.work_centre IS NULL OR a.work_centre = ?::text)
                ORDER BY e.created_at LIMIT 1
                """, (rs, i) -> rs.getString(1), tenantId, jobId, workCentre, workCentre);
        return blockers.isEmpty() ? null : blockers.get(0);
    }

    @PreAuthorize(MAINTENANCE_WRITE)
    public AssetResponse replaceCapabilities(UUID assetId, List<UUID> stageOptionIds) {
        loadAsset(assetId);
        BossContext ctx = context();
        jdbc.update("DELETE FROM asset_capabilities WHERE tenant_id = ? AND asset_id = ?", ctx.tenantId(), assetId);
        List<UUID> ids = stageOptionIds == null ? List.of() : stageOptionIds.stream().distinct().toList();
        for (UUID optionId : ids) {
            List<String> labels = jdbc.query("""
                    SELECT o.label FROM tenant_config_options o
                    JOIN config_categories c ON c.id = o.category_id
                    WHERE o.tenant_id = ? AND o.id = ? AND c.code = 'PRODUCTION_STAGES' AND o.is_active
                    """, (rs, i) -> rs.getString(1), ctx.tenantId(), optionId);
            if (labels.isEmpty()) {
                throw bad("Production stage is not a configured activity for this tenant");
            }
            jdbc.update("""
                    INSERT INTO asset_capabilities (tenant_id, asset_id, stage_option_id, stage_label)
                    VALUES (?, ?, ?, ?)
                    """, ctx.tenantId(), assetId, optionId, labels.get(0));
        }
        audit.record("asset", assetId, "CAPABILITIES", null, "{\"count\":" + ids.size() + "}");
        return loadAsset(assetId);
    }

    @PreAuthorize(MAINTENANCE_VIEW)
    @Transactional(readOnly = true)
    public List<ActivityEntry> assetActivity(UUID assetId) {
        loadAsset(assetId);
        return activity("asset", assetId);
    }

    @PreAuthorize(MAINTENANCE_VIEW)
    @Transactional(readOnly = true)
    public List<ActivityEntry> eventActivity(UUID eventId) {
        loadEvent(eventId);
        return activity("maintenance_event", eventId);
    }

    private List<ActivityEntry> activity(String entityType, UUID id) {
        return jdbc.query("""
                SELECT a.action, COALESCE(NULLIF(u.display_name,''), u.email) AS actor, a.created_at
                FROM audit_events a LEFT JOIN app_users u ON u.id = a.actor_id
                WHERE a.tenant_id = ? AND a.entity_type = ? AND a.entity_id = ? ORDER BY a.created_at DESC
                """, (rs, i) -> new ActivityEntry(rs.getString("action"), rs.getString("actor"),
                rs.getTimestamp("created_at").toInstant()), context().tenantId(), entityType, id);
    }

    // ---- helpers ---------------------------------------------------------

    private static final String EVENT_SELECT = """
            SELECT e.*, a.asset_number, a.name AS asset_name, a.site_id AS asset_site_id,
                   (SELECT COALESCE(json_agg(json_build_object('id', x.id, 'n', x.job_number)), '[]'::json)::text
                      FROM (
                        SELECT j.id, j.job_number FROM production_jobs j
                         WHERE j.tenant_id = e.tenant_id AND j.id = ANY(e.affected_job_ids)
                        UNION
                        SELECT j.id, j.job_number FROM production_jobs j
                        JOIN job_stages s ON s.tenant_id = j.tenant_id AND s.job_id = j.id
                         WHERE j.tenant_id = e.tenant_id AND s.asset_id = e.asset_id
                           AND s.status NOT IN ('completed', 'skipped')
                           AND j.status NOT IN ('completed', 'cancelled')
                      ) x) AS jobs_json
            FROM maintenance_events e JOIN assets a ON a.id = e.asset_id AND a.tenant_id = e.tenant_id
            """;

    private EventResponse loadEvent(UUID id) {
        BossContext ctx = context();
        List<EventResponse> rows = jdbc.query(EVENT_SELECT + " WHERE e.tenant_id = ? AND e.id = ?",
                eventMapper(), ctx.tenantId(), id);
        if (rows.isEmpty() || !ctx.canAccessSite(rows.get(0).siteId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Maintenance event not found");
        }
        return rows.get(0);
    }

    private AssetResponse loadAsset(UUID id) {
        BossContext ctx = context();
        List<AssetResponse> rows = jdbc.query(ASSET_SELECT + " WHERE a.tenant_id = ? AND a.id = ?",
                assetMapper(), ctx.tenantId(), id);
        if (rows.isEmpty() || !ctx.canAccessSite(rows.get(0).siteId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found");
        }
        return rows.get(0);
    }

    private List<AssetCapability> capabilities(UUID assetId) {
        return jdbc.query("""
                SELECT stage_option_id, stage_label FROM asset_capabilities
                WHERE tenant_id = ? AND asset_id = ? ORDER BY stage_label
                """, (rs, i) -> new AssetCapability(rs.getObject("stage_option_id", UUID.class), rs.getString("stage_label")),
                context().tenantId(), assetId);
    }

    private RowMapper<AssetResponse> assetMapper() {
        return (rs, i) -> new AssetResponse(rs.getObject("id", UUID.class), rs.getString("asset_number"),
                rs.getString("name"), rs.getString("category"), rs.getObject("site_id", UUID.class),
                rs.getString("site_name"), rs.getString("work_centre"), rs.getString("make"), rs.getString("model"),
                rs.getString("serial_number"), rs.getString("criticality"), rs.getString("meter_type"),
                rs.getBigDecimal("current_reading"), rs.getBigDecimal("last_service_reading"),
                date(rs.getDate("last_service_date")), date(rs.getDate("next_service_date")),
                rs.getString("pm_frequency_metric"), rs.getBigDecimal("pm_frequency_hours"),
                (Integer) rs.getObject("pm_frequency_days"), rs.getObject("responsible_user_id", UUID.class),
                rs.getString("responsible_person"), date(rs.getDate("install_date")), rs.getString("status"),
                rs.getString("archive_reason"), instant(rs.getTimestamp("archived_at")), rs.getInt("open_events"),
                instant(rs.getTimestamp("created_at")), capabilities(rs.getObject("id", UUID.class)));
    }

    private RowMapper<EventResponse> eventMapper() {
        return (rs, i) -> new EventResponse(rs.getObject("id", UUID.class), rs.getString("event_number"),
                rs.getObject("asset_id", UUID.class), rs.getString("asset_number"), rs.getString("asset_name"),
                rs.getString("event_type"), rs.getString("status"), rs.getString("severity"),
                rs.getString("symptom"), rs.getObject("technician_user_id", UUID.class),
                rs.getString("technician_name"), date(rs.getDate("planned_date")),
                rs.getBigDecimal("expected_duration_hours"), instant(rs.getTimestamp("downtime_start")),
                instant(rs.getTimestamp("downtime_end")), instant(rs.getTimestamp("completion_date")),
                rs.getBigDecimal("downtime_hours"), parseJobs(rs.getString("jobs_json")),
                rs.getString("root_cause"), rs.getString("corrective_action"), rs.getString("work_done"),
                rs.getString("resolution_notes"), rs.getString("parts_used"), instant(rs.getTimestamp("created_at")),
                rs.getObject("asset_site_id", UUID.class), rs.getString("notes"));
    }

    private List<AffectedJob> parseJobs(String json) {
        List<AffectedJob> out = new ArrayList<>();
        if (json == null || json.length() < 3) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"\\s*,\\s*\"n\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        while (m.find()) out.add(new AffectedJob(UUID.fromString(m.group(1)), m.group(2)));
        return out;
    }

    private void insertReading(BossContext ctx, UUID assetId, BigDecimal reading, BigDecimal previous,
                               LocalDate date, String type, String notes) {
        jdbc.update("""
                INSERT INTO asset_meter_readings (tenant_id, asset_id, reading, previous_reading, reading_date,
                  reading_type, recorded_by, notes) VALUES (?,?,?,?,?,?,?,?)
                """, ctx.tenantId(), assetId, reading, previous, date, type, ctx.userId(), notes);
    }

    private String nextEventNumber(UUID tenantId) {
        int year = LocalDate.now().getYear();
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))::text", String.class, "maint-" + tenantId + year);
        Integer max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(substring(event_number from '[0-9]+$')::int), 0) FROM maintenance_events
                WHERE tenant_id = ? AND event_number LIKE ?
                """, Integer.class, tenantId, "ME-" + year + "-%");
        return String.format("ME-%d-%03d", year, (max == null ? 0 : max) + 1);
    }

    private String option(String category, String value, boolean storeLabel) {
        if (value == null || value.isBlank()) throw bad(category.replace('_', ' ').toLowerCase() + " is required");
        List<String[]> rows = jdbc.query("""
                SELECT o.code, o.label FROM tenant_config_options o JOIN config_categories c ON c.id = o.category_id
                WHERE o.tenant_id = ? AND c.code = ? AND o.is_active AND (o.code = ? OR lower(o.label) = lower(?))
                """, (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, context().tenantId(), category,
                value.trim(), value.trim());
        if (rows.isEmpty()) {
            throw bad("'" + value + "' is not an active " + category.replace('_', ' ').toLowerCase() + " option");
        }
        return storeLabel ? rows.get(0)[1] : rows.get(0)[0];
    }

    private void validatePm(AssetRequest r, String meter) {
        if (r.pmFrequencyDays() != null && r.pmFrequencyDays() <= 0) throw bad("PM frequency days must be positive");
        if (r.pmFrequencyHours() != null && r.pmFrequencyHours().signum() <= 0) throw bad("PM frequency must be positive");
        if (r.currentReading() != null && r.currentReading().signum() < 0) throw bad("Meter reading cannot be negative");
        if ("none".equals(meter) && r.pmFrequencyHours() != null) throw bad("Meter-based PM needs a meter type");
    }

    private void requireSite(BossContext ctx, UUID siteId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM physical_sites WHERE tenant_id = ? AND id = ?",
                Integer.class, ctx.tenantId(), siteId);
        if (n == null || n == 0) throw bad("Site was not found for this tenant");
        if (!ctx.canAccessSite(siteId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Site is outside the caller's scope");
    }

    private String responsibleName(BossContext ctx, UUID userId, String fallback) {
        return technicianName(ctx, userId, fallback);
    }

    private String technicianName(BossContext ctx, UUID userId, String fallback) {
        if (userId != null) {
            List<String> names = jdbc.query("""
                    SELECT COALESCE(NULLIF(display_name,''), email) FROM app_users WHERE tenant_id = ? AND id = ?
                    """, (rs, i) -> rs.getString(1), ctx.tenantId(), userId);
            if (names.isEmpty()) throw bad("Technician was not found for this tenant");
            return names.get(0);
        }
        return fallback == null || fallback.isBlank() ? null : fallback.trim();
    }

    private void validateJobs(BossContext ctx, List<UUID> jobs) {
        for (UUID job : jobs) {
            List<UUID> sites = jdbc.query("SELECT site_id FROM production_jobs WHERE tenant_id = ? AND id = ?",
                    (rs, i) -> rs.getObject(1, UUID.class), ctx.tenantId(), job);
            if (sites.isEmpty() || !ctx.canAccessSite(sites.get(0))) {
                throw bad("Affected production job was not found for this tenant");
            }
        }
    }

    private String openBreakdownNumber(UUID assetId) {
        List<String> r = jdbc.query("""
                SELECT event_number FROM maintenance_events
                WHERE tenant_id = ? AND asset_id = ? AND event_type = 'Breakdown' AND status <> 'closed' LIMIT 1
                """, (rs, i) -> rs.getString(1), context().tenantId(), assetId);
        return r.isEmpty() ? null : r.get(0);
    }

    private static String uuidArray(List<UUID> ids) {
        return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
    }

    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static Timestamp ts(Instant v) { return v == null ? null : Timestamp.from(v); }
    private static Instant instant(Timestamp v) { return v == null ? null : v.toInstant(); }
    private static LocalDate date(java.sql.Date v) { return v == null ? null : v.toLocalDate(); }
    private static <T> T or(T a, T b) { return a != null ? a : b; }

    private static String required(String v, String label) {
        if (v == null || v.isBlank()) throw bad(label + " is required");
        return v;
    }

    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
    private static ResponseStatusException conflict(String m) { return new ResponseStatusException(HttpStatus.CONFLICT, m); }

    private BossContext context() {
        BossContext c = BossContextHolder.get();
        if (c == null || c.tenantId() == null || c.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant user context is required");
        }
        return c;
    }
}
