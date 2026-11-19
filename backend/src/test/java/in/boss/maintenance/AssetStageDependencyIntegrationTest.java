package in.boss.maintenance;

import in.boss.maintenance.api.MaintenanceDtos.AssetRequest;
import in.boss.maintenance.api.MaintenanceDtos.AssetResponse;
import in.boss.maintenance.api.MaintenanceDtos.AffectedStage;
import in.boss.maintenance.api.MaintenanceDtos.CloseEventRequest;
import in.boss.maintenance.api.MaintenanceDtos.EventRequest;
import in.boss.maintenance.api.MaintenanceDtos.EventResponse;
import in.boss.maintenance.domain.MaintenanceService;
import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.production.api.ProductionApiDtos.CreateRoutingTemplateRequest;
import in.boss.production.api.ProductionApiDtos.RoutingStageRequest;
import in.boss.production.domain.ProductionJobService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
class AssetStageDependencyIntegrationTest extends BaseMasterDataIntegrationTest {

    @Autowired private MaintenanceService maintenance;
    @Autowired private ProductionJobService jobs;

    private UUID tenantId;
    private UUID otherTenant;
    private UUID userId;
    private UUID siteId;
    private UUID itemId;
    private UUID uomId;
    private UUID cuttingId;
    private UUID cpoId;
    private LocalDate requiredByDate;

    @BeforeEach
    void fixtures() {
        tenantId = provisionTenant("assetstage");
        otherTenant = provisionTenant("assetstage-other");
        setTenantContext(tenantId, "OWNER");
        userId = id("""
                INSERT INTO app_users (tenant_id, firebase_uid, email, display_name)
                VALUES (?, ?, ?, 'Ravi Tech') RETURNING id
                """, tenantId, "uid-" + UUID.randomUUID(), UUID.randomUUID() + "@example.test");
        UUID entity = id("INSERT INTO legal_entities (tenant_id, name, legal_name) VALUES (?, 'E', 'E Ltd') RETURNING id", tenantId);
        siteId = id("""
                INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code)
                VALUES (?, ?, 'Plant', ?) RETURNING id
                """, tenantId, entity, "S-" + UUID.randomUUID().toString().substring(0, 6));
        uomId = id("INSERT INTO units_of_measure (tenant_id, name, symbol, is_base) VALUES (?, 'Each', 'ea', TRUE) RETURNING id", tenantId);
        itemId = id("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id)
                VALUES (?, ?, 'Frame', 'finished_goods', ?) RETURNING id
                """, tenantId, "FG-" + UUID.randomUUID().toString().substring(0, 6), uomId);
        UUID customerId = id("""
                INSERT INTO customers (tenant_id, code, name) VALUES (?, ?, 'Acme') RETURNING id
                """, tenantId, "C-" + UUID.randomUUID().toString().substring(0, 6));
        requiredByDate = LocalDate.now().plusDays(20);
        cpoId = id("""
                INSERT INTO cpos (tenant_id, cpo_number, customer_id, site_id, order_date, required_by_date, created_by)
                VALUES (?, ?, ?, ?, CURRENT_DATE, ?, ?) RETURNING id
                """, tenantId, "PO-" + UUID.randomUUID().toString().substring(0, 6), customerId,
                siteId, requiredByDate, userId);
        jdbc.queryForObject("SELECT seed_tenant_defaults(?, 'GENERAL_MANUFACTURING')", Integer.class, tenantId);
        jdbc.queryForObject("SELECT seed_tenant_defaults(?, 'GENERAL_MANUFACTURING')", Integer.class, otherTenant);
        cuttingId = jdbc.queryForObject("""
                SELECT o.id FROM tenant_config_options o
                JOIN config_categories c ON c.id = o.category_id
                WHERE o.tenant_id = ? AND c.code = 'PRODUCTION_STAGES'
                ORDER BY o.label LIMIT 1
                """, UUID.class, tenantId);
        BossContextHolder.set(new BossContext(tenantId, userId, Set.of("OWNER"), siteId, null));
    }

    @Test
    void assetCapabilitiesGateEveryLinkedStageAndLeaveOtherAssetsAlone() {
        AssetResponse lathe = maintenance.createAsset(asset("LTH-1"));
        AssetResponse spare = maintenance.createAsset(asset("LTH-2"));
        AssetResponse mill = maintenance.createAsset(asset("MIL-1"));
        assertThat(lathe.capabilities()).isEmpty();

        AssetResponse capable = maintenance.replaceCapabilities(lathe.id(), List.of(cuttingId));
        assertThat(capable.capabilities()).extracting(c -> c.stageOptionId()).containsExactly(cuttingId);
        maintenance.replaceCapabilities(spare.id(), List.of(cuttingId));

        UUID otherStage = jdbc.queryForObject("""
                SELECT o.id FROM tenant_config_options o
                JOIN config_categories c ON c.id = o.category_id
                WHERE o.tenant_id = ? AND c.code = 'PRODUCTION_STAGES' AND o.id <> ?
                ORDER BY o.label LIMIT 1
                """, UUID.class, tenantId, cuttingId);
        AssetResponse updated = maintenance.replaceCapabilities(lathe.id(), List.of(cuttingId, otherStage));
        assertThat(updated.capabilities()).hasSize(2);

        assertThatThrownBy(() -> jobs.createRoutingTemplate(new CreateRoutingTemplateRequest(
                "Incompatible", null, List.of(new RoutingStageRequest("Cutting", null, mill.id(), cuttingId)))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not support");
        jobs.createRoutingTemplate(new CreateRoutingTemplateRequest(
                "Compatible", null, List.of(new RoutingStageRequest("Cutting", "CNC Bay", lathe.id(), cuttingId))));

        UUID jobA = job("JOB-A", lathe.id());
        UUID jobB = job("JOB-B", lathe.id());
        UUID otherAssetJob = job("JOB-C", spare.id());
        UUID unrelated = job("JOB-D", mill.id());
        jdbc.update("UPDATE production_jobs SET cpo_id = ? WHERE tenant_id = ? AND id = ?", cpoId, tenantId, jobA);
        UUID stageA = stage(jobA);
        UUID stageB = stage(jobB);

        assertThat(maintenance.machineBlocker(tenantId, jobA, stageA, "CNC Bay")).isNull();

        EventResponse event = maintenance.createEvent(new EventRequest(lathe.id(), "Breakdown", "High", "Spindle",
                userId, null, null, null, Instant.now().minus(2, ChronoUnit.HOURS), null, null, List.of(), null));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications
                WHERE tenant_id = ? AND user_id = ? AND notification_type = 'maintenance_asset_impact'
                  AND entity_type = 'maintenance_event' AND entity_id = ?
                """, Integer.class, tenantId, userId, event.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT body FROM notifications WHERE tenant_id = ? AND user_id = ? AND entity_id = ?
                """, String.class, tenantId, userId, event.id())).contains("production stage(s)");
        assertThat(maintenance.machineBlocker(tenantId, jobA, stageA, "CNC Bay")).contains(lathe.assetCode());
        assertThat(maintenance.machineBlocker(tenantId, jobB, stageB, "CNC Bay")).contains(event.eventNumber());
        assertThat(maintenance.machineBlocker(tenantId, otherAssetJob, stage(otherAssetJob), "CNC Bay")).isNull();
        assertThat(maintenance.machineBlocker(tenantId, unrelated, stage(unrelated), "CNC Bay")).isNull();
        EventResponse impacted = maintenance.getEvent(event.id());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications
                WHERE tenant_id = ? AND user_id = ? AND notification_type = 'maintenance_asset_impact'
                  AND entity_type = 'maintenance_event' AND entity_id = ?
                """, Integer.class, tenantId, userId, event.id())).isEqualTo(1);
        assertThat(impacted.affectedJobs()).extracting(j -> j.id()).contains(jobA, jobB);
        AffectedStage impactedStage = impacted.affectedStages().stream()
                .filter(s -> s.jobId().equals(jobA)).findFirst().orElseThrow();
        assertThat(impactedStage.name()).isEqualTo("Cutting");
        assertThat(impactedStage.customerOrderNumber()).isNotBlank();
        assertThat(impactedStage.requiredByDate()).isEqualTo(requiredByDate);
        assertThat(impactedStage.alternatives()).extracting(a -> a.assetCode()).contains("LTH-2");

        UUID otherTenantStage = jdbc.queryForObject("""
                SELECT o.id FROM tenant_config_options o
                JOIN config_categories c ON c.id = o.category_id
                WHERE o.tenant_id = ? AND c.code = 'PRODUCTION_STAGES'
                ORDER BY o.label LIMIT 1
                """, UUID.class, otherTenant);
        assertThatThrownBy(() -> maintenance.replaceCapabilities(lathe.id(), List.of(otherTenantStage)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not a configured activity for this tenant");
        assertThat(maintenance.getAsset(lathe.id()).capabilities()).hasSize(2);

        EventResponse closed = maintenance.closeEvent(event.id(),
                new CloseEventRequest(null, "Belt", "Replaced belt", null, null, "Belt"));
        assertThat(closed.downtimeHours()).isNotNull();
        assertThat(maintenance.getAsset(lathe.id()).status()).isEqualTo("operational");
        assertThat(maintenance.machineBlocker(tenantId, jobA, stageA, "CNC Bay")).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM job_stages WHERE id = ?", String.class, stageA)).isEqualTo("pending");

        BossContextHolder.set(new BossContext(otherTenant, userId, Set.of("OWNER"), null, null));
        assertThatThrownBy(() -> maintenance.getAsset(lathe.id())).isInstanceOf(ResponseStatusException.class);
    }

    private UUID job(String number, UUID assetId) {
        UUID job = id("""
                INSERT INTO production_jobs (tenant_id, job_number, finished_item_id, site_id, quantity, uom_id, status, created_by)
                VALUES (?, ?, ?, ?, 5, ?, 'released', ?) RETURNING id
                """, tenantId, number, itemId, siteId, uomId, userId);
        jdbc.update("""
                INSERT INTO job_stages (tenant_id, job_id, seq_no, name, stage_config_option_id, stage_label_snapshot,
                                        work_centre, asset_id, status)
                VALUES (?, ?, 1, 'Cutting', ?, 'Cutting', 'CNC Bay', ?, 'pending')
                """, tenantId, job, cuttingId, assetId);
        return job;
    }

    private UUID stage(UUID jobId) {
        return jdbc.queryForObject("SELECT id FROM job_stages WHERE job_id = ?", UUID.class, jobId);
    }

    private UUID id(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }

    private AssetRequest asset(String code) {
        return new AssetRequest(code, "Lathe " + code, "Machining Equipment", siteId, "CNC Bay", "Haas", "ST-20",
                "SN1", "High", "hours", new BigDecimal("100"), null, LocalDate.now().minusDays(30), null, "hours",
                new BigDecimal("500"), 90, userId, null, null, null, null);
    }
}
