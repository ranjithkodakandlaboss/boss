package in.boss.inventory;

import in.boss.inventory.api.InventoryApiDtos.CreateLotRequest;
import in.boss.inventory.domain.FeasibilityCheckService;
import in.boss.inventory.domain.InventoryService;
import in.boss.inventory.domain.ReservationConflictException;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeasibilityCheckTest extends InventoryIntegrationTest {

    @Autowired
    private FeasibilityCheckService feasibility;

    @Autowired
    private InventoryService inventoryService;

    private Seed seed;
    private UUID resinId;
    private UUID boltId;
    private UUID finishedId;

    @BeforeEach
    void setUp() {
        seed = seed("feasibility");
        resinId = seed.itemId();
        boltId = item("BOLT", "raw_material");
        finishedId = item("FG", "finished_goods");
        UUID bomId = insert("""
                INSERT INTO bom_headers (tenant_id, finished_item_id, version, status)
                VALUES (?, ?, 'v1', 'approved') RETURNING id
                """, seed.tenantId(), finishedId);
        bomLine(bomId, resinId, "2");
        bomLine(bomId, boltId, "1");
        asKeeper();
    }

    @Test
    void reservesEveryBomMaterialWhenStockIsSufficient() {
        UUID cpoId = cpo("open", "5");
        lot(resinId, "6");
        lot(resinId, "6");
        lot(boltId, "5");

        var result = feasibility.checkAndReserve(cpoId);

        assertThat(result.feasible()).isTrue();
        assertThat(reserved(resinId)).isEqualByComparingTo("10.000");
        assertThat(reserved(boltId)).isEqualByComparingTo("5.000");
        assertThat(activeRecords(cpoId)).isEqualTo(3);
        assertThatThrownBy(() -> feasibility.checkAndReserve(cpoId))
                .isInstanceOf(ReservationConflictException.class);
    }

    @Test
    void shortageOfAnyMaterialReservesNothing() {
        UUID cpoId = cpo("open", "5");
        lot(resinId, "10");
        lot(boltId, "4");

        var result = feasibility.checkAndReserve(cpoId);

        assertThat(result.feasible()).isFalse();
        assertThat(result.materials()).filteredOn(m -> m.shortfall().signum() > 0)
                .singleElement().satisfies(m -> {
                    assertThat(m.itemId()).isEqualTo(boltId);
                    assertThat(m.shortfall()).isEqualByComparingTo("1.000");
                });
        assertThat(reserved(resinId)).isEqualByComparingTo("0.000");
        assertThat(reserved(boltId)).isEqualByComparingTo("0.000");
        assertThat(activeRecords(cpoId)).isZero();
    }

    @Test
    void releaseRestoresStockAfterCancellation() {
        UUID cpoId = cpo("open", "5");
        lot(resinId, "10");
        lot(boltId, "5");
        assertThat(feasibility.checkAndReserve(cpoId).feasible()).isTrue();

        feasibility.releaseReservations(cpoId);

        assertThat(reserved(resinId)).isEqualByComparingTo("0.000");
        assertThat(reserved(boltId)).isEqualByComparingTo("0.000");
        assertThat(activeRecords(cpoId)).isZero();
        assertThat(feasibility.checkAndReserve(cpoId).feasible()).isTrue();
    }

    @Test
    void concurrentChecksNeverOversellMaterials() throws Exception {
        lot(resinId, "20");
        lot(boltId, "10");
        List<UUID> cpos = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            cpos.add(cpo("open", "5"));
        }
        var executor = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        try {
            var attempts = cpos.stream().map(cpo -> executor.submit(() -> {
                BossContextHolder.set(new BossContext(seed.tenantId(), UUID.randomUUID(),
                        Set.of("STORE_KEEPER"), null, null));
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("did not start");
                    }
                    return feasibility.checkAndReserve(cpo).feasible();
                } finally {
                    BossContextHolder.clear();
                }
            })).toList();
            start.countDown();
            long successful = 0;
            for (var attempt : attempts) {
                if (attempt.get(30, TimeUnit.SECONDS)) {
                    successful++;
                }
            }
            assertThat(successful).isEqualTo(2);
            assertThat(reserved(resinId)).isEqualByComparingTo("20.000");
            assertThat(reserved(boltId)).isEqualByComparingTo("10.000");
        } finally {
            executor.shutdownNow();
        }
    }

    private UUID item(String prefix, String type) {
        return insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, seed.tenantId(), prefix + "-" + UUID.randomUUID().toString().substring(0, 8),
                prefix, type, seed.uomId());
    }

    private void bomLine(UUID bomId, UUID componentId, String qty) {
        jdbc.update("""
                INSERT INTO bom_lines (tenant_id, bom_header_id, component_item_id, quantity, uom_id, level)
                VALUES (?, ?, ?, ?, ?, 1)
                """, seed.tenantId(), bomId, componentId, new BigDecimal(qty), seed.uomId());
    }

    private UUID cpo(String status, String qty) {
        UUID customerId = insert("""
                INSERT INTO customers (tenant_id, code, name)
                VALUES (?, ?, 'Feasibility Customer') RETURNING id
                """, seed.tenantId(), "CUST-" + UUID.randomUUID().toString().substring(0, 8));
        UUID cpoId = insert("""
                INSERT INTO cpos
                  (tenant_id, cpo_number, customer_id, site_id, status, order_date, created_by)
                VALUES (?, ?, ?, ?, ?, CURRENT_DATE, ?) RETURNING id
                """, seed.tenantId(), "CPO-" + UUID.randomUUID().toString().substring(0, 8),
                customerId, seed.siteId(), status, UUID.randomUUID());
        jdbc.update("""
                INSERT INTO cpo_lines (tenant_id, cpo_id, line_no, item_id, quantity, uom_id, unit_price)
                VALUES (?, ?, 1, ?, ?, ?, 1)
                """, seed.tenantId(), cpoId, finishedId, new BigDecimal(qty), seed.uomId());
        return cpoId;
    }

    private void lot(UUID itemId, String qty) {
        inventoryService.createLot(new CreateLotRequest(itemId, seed.storeId(), new BigDecimal(qty),
                BigDecimal.ONE, null, null, null, null));
    }

    private BigDecimal reserved(UUID itemId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved_qty), 0) FROM material_lots
                 WHERE tenant_id = ? AND item_id = ?
                """, BigDecimal.class, seed.tenantId(), itemId);
    }

    private int activeRecords(UUID cpoId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM stock_reservation_records
                 WHERE tenant_id = ? AND reference_id = ? AND status = 'active'
                """, Integer.class, seed.tenantId(), cpoId);
    }

    private void asKeeper() {
        setTenantContext(seed.tenantId(), "STORE_KEEPER");
    }
}
