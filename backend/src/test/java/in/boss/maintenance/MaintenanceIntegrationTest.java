package in.boss.maintenance;

import in.boss.maintenance.api.MaintenanceDtos.*;
import in.boss.maintenance.domain.MaintenanceService;
import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
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

@WithMockUser(roles = "OWNER")
class MaintenanceIntegrationTest extends BaseMasterDataIntegrationTest {

    @Autowired private MaintenanceService maintenance;

    private UUID tenantId;
    private UUID siteId;
    private UUID userId;

    @BeforeEach
    void fixtures() {
        tenantId = provisionTenant("maint");
        setTenantContext(tenantId, "OWNER");
        userId = insert("""
                INSERT INTO app_users (tenant_id, firebase_uid, email, display_name)
                VALUES (?, ?, ?, 'Ravi Tech') RETURNING id
                """, tenantId, "uid-" + UUID.randomUUID(), UUID.randomUUID() + "@example.test");
        UUID legalEntityId = insert("""
                INSERT INTO legal_entities (tenant_id, name, legal_name)
                VALUES (?, 'Entity', 'Entity Ltd') RETURNING id
                """, tenantId);
        siteId = insert("""
                INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code)
                VALUES (?, ?, 'Plant', ?) RETURNING id
                """, tenantId, legalEntityId, "S-" + UUID.randomUUID().toString().substring(0, 6));
        BossContextHolder.set(new BossContext(tenantId, userId, Set.of("OWNER"), siteId, null));
        jdbc.queryForObject("SELECT seed_tenant_defaults(?, 'GENERAL_MANUFACTURING')", Integer.class, tenantId);
    }

    private UUID insert(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }

    private AssetRequest asset(String code) {
        return new AssetRequest(code, "Lathe " + code, "Machining Equipment", siteId, "CNC Bay", "Haas", "ST-20",
                "SN1", "High", "hours", new BigDecimal("100"), null, LocalDate.now().minusDays(30), null, "hours",
                new BigDecimal("500"), 90, userId, null, null, null, null);
    }

    private EventRequest breakdown(UUID assetId) {
        return new EventRequest(assetId, "Breakdown", "High", "Spindle stalls", userId, null, null, null,
                Instant.now().minus(3, ChronoUnit.HOURS), null, null, List.of(), null);
    }

    @Test
    void newTenantReceivesDbBackedDropdownsAndTheyAreTenantScoped() {
        Integer categories = jdbc.queryForObject("""
                SELECT count(*) FROM tenant_config_options o JOIN config_categories c ON c.id = o.category_id
                WHERE o.tenant_id = ? AND c.code IN ('ASSET_CATEGORY','WORK_CENTRE','ASSET_CRITICALITY','METER_TYPE',
                  'MAINTENANCE_EVENT_TYPE','MAINTENANCE_SEVERITY','ASSET_STATUS','MAINTENANCE_EVENT_STATUS')
                """, Integer.class, tenantId);
        assertThat(categories).isEqualTo(39);
        Lookups lookups = maintenance.lookups();
        assertThat(lookups.sites()).extracting(SiteOption::id).containsExactly(siteId);
        assertThat(lookups.technicians()).extracting(TechnicianOption::name).contains("Ravi Tech");
    }

    @Test
    void assetLifecycleKeepsStatusAndServiceDatesConsistent() {
        AssetResponse created = maintenance.createAsset(asset("LTH-1"));
        assertThat(created.status()).isEqualTo("operational");
        assertThat(created.category()).isEqualTo("Machining Equipment");
        assertThatThrownBy(() -> maintenance.createAsset(asset("lth-1")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        AssetRequest bad = new AssetRequest("LTH-2", "x", "Not A Category", siteId, null, "Haas", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> maintenance.createAsset(bad))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        EventResponse event = maintenance.createEvent(breakdown(created.id()));
        assertThat(event.eventNumber()).isEqualTo("ME-" + LocalDate.now().getYear() + "-001");
        assertThat(maintenance.getAsset(created.id()).status()).isEqualTo("breakdown");
        AssetRequest operational = new AssetRequest(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, "operational", null);
        assertThatThrownBy(() -> maintenance.updateAsset(created.id(), operational))
                .hasMessageContaining("breakdown " + event.eventNumber() + " is unresolved");
        assertThatThrownBy(() -> maintenance.archiveAsset(created.id(), new ArchiveRequest("old")))
                .hasMessageContaining("still open");
        assertThatThrownBy(() -> maintenance.closeEvent(event.id(), new CloseEventRequest(null, null, null, null, null, null)))
                .hasMessageContaining("Work done");

        EventResponse closed = maintenance.closeEvent(event.id(),
                new CloseEventRequest(null, "Worn belt", "Replaced belt", null, null, "Belt"));
        assertThat(closed.status()).isEqualTo("closed");
        assertThat(closed.downtimeHours()).isGreaterThan(new BigDecimal("2.9"));
        AssetResponse after = maintenance.getAsset(created.id());
        assertThat(after.status()).isEqualTo("operational");
        assertThat(after.lastServiceDate()).isEqualTo(LocalDate.now());
        assertThat(after.nextServiceDate()).isEqualTo(LocalDate.now().plusDays(90));
        assertThat(maintenance.createEvent(breakdown(created.id())).eventNumber()).endsWith("-002");

        maintenance.markRepaired(created.id(), new RepairRequest(null, "Reset drive", null));
        assertThat(maintenance.getAsset(created.id()).status()).isEqualTo("operational");
        AssetResponse archived = maintenance.archiveAsset(created.id(), new ArchiveRequest("Sold"));
        assertThat(archived.status()).isEqualTo("retired");
        assertThat(maintenance.listAssets(false)).isEmpty();
        assertThat(maintenance.listAssets(true)).hasSize(1);
        assertThat(maintenance.listEvents(created.id(), null)).hasSize(2);
        assertThatThrownBy(() -> maintenance.createEvent(breakdown(created.id()))).hasMessageContaining("retired");
    }

    @Test
    void meterReadingsAreAppendedAndPreventiveEventsDoNotBreakTheAsset() {
        AssetResponse a = maintenance.createAsset(asset("LTH-3"));
        maintenance.logReading(a.id(), new MeterReadingRequest(new BigDecimal("150"), null, "routine", "shift"));
        assertThatThrownBy(() -> maintenance.logReading(a.id(), new MeterReadingRequest(new BigDecimal("10"), null, "routine", null)))
                .hasMessageContaining("lower");
        AssetRequest edit = new AssetRequest(null, null, null, null, null, null, null, null, null, null,
                new BigDecimal("160"), null, null, null, null, null, null, null, null, null, null, "note");
        maintenance.updateAsset(a.id(), edit);
        assertThat(maintenance.readings(a.id())).extracting(MeterReadingResponse::reading)
                .containsExactly(new BigDecimal("160.00"), new BigDecimal("150.00"), new BigDecimal("100.00"));

        EventResponse pm = maintenance.createEvent(new EventRequest(a.id(), "Preventive", null, "Quarterly PM", null,
                "Ravi", LocalDate.now().plusDays(2), new BigDecimal("4"), null, null, null, List.of(), null));
        assertThat(pm.status()).isEqualTo("scheduled");
        assertThat(maintenance.getAsset(a.id()).status()).isEqualTo("operational");
        maintenance.startEvent(pm.id());
        assertThat(maintenance.getAsset(a.id()).status()).isEqualTo("in_maintenance");
        maintenance.closeEvent(pm.id(), new CloseEventRequest(null, null, "Serviced", null, null, null));
        AssetResponse after = maintenance.getAsset(a.id());
        assertThat(after.status()).isEqualTo("operational");
        assertThat(after.lastServiceReading()).isEqualByComparingTo("160");
    }

    @Test
    void breakdownValidationAndProductionBlocker() {
        AssetResponse a = maintenance.createAsset(asset("LTH-4"));
        Instant start = Instant.now().minus(2, ChronoUnit.HOURS);
        assertThatThrownBy(() -> maintenance.createEvent(new EventRequest(a.id(), "Breakdown", "High", "x", userId, null,
                null, null, start, start.minus(1, ChronoUnit.HOURS), null, List.of(), null)))
                .hasMessageContaining("earlier");
        assertThatThrownBy(() -> maintenance.createEvent(new EventRequest(a.id(), "Preventive", null, "x", userId, null,
                null, null, null, null, null, List.of(), null))).hasMessageContaining("Planned date");

        EventResponse e = maintenance.createEvent(breakdown(a.id()));
        UUID jobId = UUID.randomUUID();
        assertThat(maintenance.machineBlocker(tenantId, jobId, "CNC Bay")).isNull();
        jdbc.update("UPDATE maintenance_events SET affected_job_ids = ARRAY[?::uuid] WHERE id = ?", jobId, e.id());
        assertThat(maintenance.machineBlocker(tenantId, jobId, "CNC Bay")).contains(e.eventNumber());
        assertThat(maintenance.machineBlocker(tenantId, jobId, "Press Shop")).isNull();
        maintenance.closeEvent(e.id(), new CloseEventRequest(null, null, "Fixed", null, null, null));
        assertThat(maintenance.machineBlocker(tenantId, jobId, "CNC Bay")).isNull();
    }

    @Test
    void assetsAreInvisibleToOtherTenants() {
        AssetResponse a = maintenance.createAsset(asset("LTH-5"));
        UUID other = provisionTenant("maint-other");
        BossContextHolder.set(new BossContext(other, UUID.randomUUID(), Set.of("OWNER"), null, null));
        assertThat(maintenance.listAssets(true)).isEmpty();
        assertThatThrownBy(() -> maintenance.getAsset(a.id()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @WithMockUser(roles = "FIELD_WORKER")
    void fieldWorkersCanReadButNotWrite() {
        assertThat(maintenance.listAssets(false)).isEmpty();
        assertThatThrownBy(() -> maintenance.createAsset(asset("LTH-6"))).isInstanceOf(AccessDeniedException.class);
    }
}
