package in.boss.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.maintenance.api.MaintenanceDtos.*;
import in.boss.maintenance.domain.MaintenanceService;
import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.shared.config.ConfigService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithMockUser(roles = "OWNER")
@AutoConfigureMockMvc
class MaintenanceFollowUpIntegrationTest extends BaseMasterDataIntegrationTest {

    @Autowired private MaintenanceService maintenance;
    @Autowired private ConfigService config;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private UUID tenantId;
    private UUID userId;
    private UUID siteA;
    private UUID siteB;
    private UUID uomId;
    private UUID itemId;

    @BeforeEach
    void fixtures() {
        tenantId = provisionTenant("maintfu");
        setTenantContext(tenantId, "OWNER");
        userId = id("""
                INSERT INTO app_users (tenant_id, firebase_uid, email, display_name)
                VALUES (?, ?, ?, 'Ravi Tech') RETURNING id
                """, tenantId, "uid-" + UUID.randomUUID(), UUID.randomUUID() + "@example.test");
        UUID entity = id("INSERT INTO legal_entities (tenant_id, name, legal_name) VALUES (?, 'E', 'E Ltd') RETURNING id", tenantId);
        siteA = site(entity, "Plant A");
        siteB = site(entity, "Plant B");
        uomId = id("INSERT INTO units_of_measure (tenant_id, name, symbol, is_base) VALUES (?, 'Each', 'ea', TRUE) RETURNING id", tenantId);
        itemId = id("INSERT INTO items (tenant_id, code, name, item_type, base_uom_id) VALUES (?, ?, 'Frame', 'finished_goods', ?) RETURNING id",
                tenantId, "FG-" + UUID.randomUUID().toString().substring(0, 6), uomId);
        jdbc.queryForObject("SELECT seed_tenant_defaults(?, 'GENERAL_MANUFACTURING')", Integer.class, tenantId);
        as(siteA);
    }

    private UUID id(String sql, Object... args) { return jdbc.queryForObject(sql, UUID.class, args); }

    private UUID site(UUID entity, String name) {
        return id("INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code) VALUES (?, ?, ?, ?) RETURNING id",
                tenantId, entity, name, "S-" + UUID.randomUUID().toString().substring(0, 6));
    }

    private void as(UUID site) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("owner", "n/a",
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OWNER"))));
        BossContextHolder.set(new BossContext(tenantId, userId, Set.of("OWNER"), site, null));
    }

    private AssetRequest asset(String code, UUID site, String workCentre) {
        return new AssetRequest(code, "Lathe " + code, "Machining Equipment", site, workCentre, "Haas", "ST-20",
                "SN1", "High", "hours", new BigDecimal("100"), null, LocalDate.now().minusDays(30), null, "hours",
                new BigDecimal("500"), 90, userId, null, null, null, null);
    }

    private EventRequest breakdown(UUID assetId, List<UUID> jobs) {
        return new EventRequest(assetId, "Breakdown", "High", "Spindle stalls", userId, null, null, null,
                Instant.now().minus(3, ChronoUnit.HOURS), null, null, jobs, null);
    }

    private UUID job(UUID site, String number, String workCentre) {
        UUID job = id("""
                INSERT INTO production_jobs (tenant_id, job_number, finished_item_id, site_id, quantity, uom_id, status, created_by)
                VALUES (?, ?, ?, ?, 5, ?, 'released', ?) RETURNING id
                """, tenantId, number, itemId, site, uomId, userId);
        jdbc.update("""
                INSERT INTO job_stages (tenant_id, job_id, seq_no, name, work_centre) VALUES (?, ?, 1, 'Turning', ?)
                """, tenantId, job, workCentre);
        return job;
    }

    private UUID stage(UUID job) {
        return id("SELECT id FROM job_stages WHERE job_id = ?", job);
    }

    private static void assertStatus(Runnable r, HttpStatus expected) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(expected));
    }

    @Test
    void siteScopedUserCannotSeeOrManageOtherSiteEvents() {
        as(null);
        AssetResponse assetB = maintenance.createAsset(asset("LTH-B", siteB, "CNC Bay"));
        EventResponse eventB = maintenance.createEvent(breakdown(assetB.id(), List.of()));
        AssetResponse assetA = maintenance.createAsset(asset("LTH-A", siteA, "CNC Bay"));
        EventResponse eventA = maintenance.createEvent(breakdown(assetA.id(), List.of()));

        as(siteA);
        assertThat(maintenance.listEvents(null, null)).extracting(EventResponse::id).containsExactly(eventA.id());
        assertStatus(() -> maintenance.getEvent(eventB.id()), HttpStatus.NOT_FOUND);
        assertStatus(() -> maintenance.startEvent(eventB.id()), HttpStatus.NOT_FOUND);
        assertStatus(() -> maintenance.closeEvent(eventB.id(),
                new CloseEventRequest(null, null, "Fixed", null, null, null)), HttpStatus.NOT_FOUND);
        assertStatus(() -> maintenance.eventActivity(eventB.id()), HttpStatus.NOT_FOUND);
        assertStatus(() -> maintenance.createEvent(breakdown(assetB.id(), List.of())), HttpStatus.NOT_FOUND);

        assertThat(maintenance.startEvent(eventA.id()).status()).isEqualTo("in_progress");
        assertThat(maintenance.closeEvent(eventA.id(),
                new CloseEventRequest(null, "Belt", "Replaced", null, null, null)).status()).isEqualTo("closed");
        assertThat(jdbc.queryForObject("SELECT status FROM maintenance_events WHERE id = ?", String.class, eventB.id()))
                .isEqualTo("open");

        as(null);
        assertThat(maintenance.listEvents(null, null)).hasSize(2);
        assertThat(maintenance.getEvent(eventB.id()).siteId()).isEqualTo(siteB);
        assertThat(maintenance.startEvent(eventB.id()).status()).isEqualTo("in_progress");
    }

    @Test
    void siteScopedUserCannotLinkOrSeeOtherSiteJobs() {
        UUID jobA = job(siteA, "JOB-A", "CNC Bay");
        UUID jobB = job(siteB, "JOB-B", "CNC Bay");
        AssetResponse assetA = maintenance.createAsset(asset("LTH-A2", siteA, "CNC Bay"));

        assertThat(maintenance.lookups().jobs()).extracting(JobOption::id).containsExactly(jobA);
        assertStatus(() -> maintenance.createEvent(breakdown(assetA.id(), List.of(jobB))), HttpStatus.BAD_REQUEST);
        assertThat(maintenance.createEvent(breakdown(assetA.id(), List.of(jobA))).affectedJobs()).hasSize(1);

        as(null);
        assertThat(maintenance.lookups().jobs()).extracting(JobOption::id).containsExactlyInAnyOrder(jobA, jobB);
    }

    @Test
    void openBreakdownBlocksStageStartThroughProductionApiUntilClosed() throws Exception {
        UUID jobA = job(siteA, "JOB-1", "CNC Bay");
        UUID unrelated = job(siteA, "JOB-2", "CNC Bay");
        UUID otherCentre = job(siteA, "JOB-3", "Press Shop");
        AssetResponse asset = maintenance.createAsset(asset("LTH-P", siteA, "CNC Bay"));
        EventResponse event = maintenance.createEvent(breakdown(asset.id(), List.of(jobA, otherCentre)));

        startStage(otherCentre).andExpect(status().isOk());
        startStage(unrelated).andExpect(status().isOk());
        startStage(jobA).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM job_stages WHERE job_id = ?", String.class, jobA))
                .isEqualTo("pending");

        as(siteA);
        maintenance.startEvent(event.id());
        maintenance.closeEvent(event.id(), new CloseEventRequest(null, "Belt", "Replaced belt", null, null, null));
        startStage(jobA).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM job_stages WHERE job_id = ?", String.class, jobA))
                .isEqualTo("in_progress");
    }

    @Test
    void repeatedBreakdownCreateRequestCreatesOnlyOneOwnerNotification() throws Exception {
        AssetResponse asset = maintenance.createAsset(asset("LTH-NOTIFY", siteA, "CNC Bay"));
        String key = UUID.randomUUID().toString();
        String request = objectMapper.writeValueAsString(breakdown(asset.id(), List.of()));

        for (int attempt = 0; attempt < 2; attempt++) {
            as(siteA);
            mockMvc.perform(post("/api/v1/maintenance/events")
                            .with(user("owner").roles("OWNER"))
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                    .andExpect(status().isCreated());
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM maintenance_events
                WHERE tenant_id = ? AND asset_id = ?
                """, Integer.class, tenantId, asset.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications
                WHERE tenant_id = ? AND user_id = ? AND notification_type = 'maintenance_asset_impact'
                  AND entity_type = 'maintenance_event'
                """, Integer.class, tenantId, userId)).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions startStage(UUID job) throws Exception {
        as(siteA);
        return mockMvc.perform(post("/api/v1/jobs/" + job + "/stages/" + stage(job) + "/start")
                .with(user("owner").roles("OWNER"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    @Test
    void inlineOptionCreationIsAllowedForTenantCategoriesAndRefusedForSystemOnes() throws Exception {
        for (String category : List.of("ASSET_CATEGORY", "WORK_CENTRE", "ASSET_CRITICALITY", "MAINTENANCE_SEVERITY")) {
            var created = config.createOption(category, "NEW_" + category, "New " + category, 99);
            assertThat(config.getOptions(category)).extracting(o -> o.code()).contains(created.code());
        }
        assertStatus(() -> config.createOption("WORK_CENTRE", "other", "new work_centre", 1), HttpStatus.CONFLICT);
        for (String category : List.of("ASSET_STATUS", "METER_TYPE", "MAINTENANCE_EVENT_TYPE", "MAINTENANCE_EVENT_STATUS")) {
            assertStatus(() -> config.createOption(category, "HACK", "Hack", 1), HttpStatus.FORBIDDEN);
        }
        assertStatus(() -> config.createOption("NOT_A_CATEGORY", "X", "X", 1), HttpStatus.BAD_REQUEST);
        mockMvc.perform(post("/api/v1/config/options/ASSET_STATUS")
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"HACK\",\"label\":\"Hack\",\"sortOrder\":1}"))
                .andExpect(status().isForbidden());

        UUID other = provisionTenant("maintfu-other");
        BossContextHolder.set(new BossContext(other, UUID.randomUUID(), Set.of("OWNER"), null, null));
        assertThat(config.getOptions("WORK_CENTRE")).extracting(o -> o.code()).doesNotContain("NEW_WORK_CENTRE");
    }

    @Test
    void eventNotesAndClosureDetailsArePersistedWhenCompletedAtCreation() {
        AssetResponse asset = maintenance.createAsset(asset("LTH-N", siteA, "CNC Bay"));
        Instant down = micros(Instant.now().minus(5, ChronoUnit.HOURS));
        Instant done = micros(Instant.now().minus(1, ChronoUnit.HOURS));
        EventResponse e = maintenance.createEvent(new EventRequest(asset.id(), "Breakdown", "High", "Overheating",
                null, "Acme Contractors", null, null, down, null, done, List.of(), "Cleaned filter", "Watch temp",
                "Clogged filter", "Replaced filter", "Closed after test run", "Filter x1"));
        assertThat(e.status()).isEqualTo("closed");
        assertThat(e.notes()).isEqualTo("Watch temp");
        assertThat(e.technicianName()).isEqualTo("Acme Contractors");
        assertThat(e.technicianUserId()).isNull();
        assertThat(e.correctiveAction()).isEqualTo("Cleaned filter");
        assertThat(e.rootCause()).isEqualTo("Clogged filter");
        assertThat(e.workDone()).isEqualTo("Replaced filter");
        assertThat(e.resolutionNotes()).isEqualTo("Closed after test run");
        assertThat(e.partsUsed()).isEqualTo("Filter x1");
        assertThat(e.completionDate()).isEqualTo(done);
    }

    @Test
    void markRepairedPersistsCompletionTimeRootCauseAndWorkDone() {
        AssetResponse asset = maintenance.createAsset(asset("LTH-R", siteA, "CNC Bay"));
        EventResponse e = maintenance.createEvent(breakdown(asset.id(), List.of()));
        assertStatus(() -> maintenance.markRepaired(asset.id(), new RepairRequest(null, "x", "y", null)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> maintenance.markRepaired(asset.id(),
                new RepairRequest(Instant.now().minus(1, ChronoUnit.HOURS), " ", "y", null)), HttpStatus.BAD_REQUEST);
        Instant done = micros(Instant.now().minus(30, ChronoUnit.MINUTES));
        maintenance.markRepaired(asset.id(), new RepairRequest(done, "Worn bearing", "Replaced bearing", "Test run ok"));
        EventResponse closed = maintenance.getEvent(e.id());
        assertThat(closed.status()).isEqualTo("closed");
        assertThat(closed.completionDate()).isEqualTo(done);
        assertThat(closed.rootCause()).isEqualTo("Worn bearing");
        assertThat(closed.workDone()).isEqualTo("Replaced bearing");
        assertThat(closed.resolutionNotes()).isEqualTo("Test run ok");
        assertThat(maintenance.getAsset(asset.id()).status()).isEqualTo("operational");
        assertThat(maintenance.assetActivity(asset.id())).isNotNull();
    }

    /** PostgreSQL timestamptz stores microseconds and rounds. A finer instant can come back one microsecond later. */
    private static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }
}
