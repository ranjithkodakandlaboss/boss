package in.boss.masterdata;

import in.boss.masterdata.api.dto.CreateItemRequest;
import in.boss.masterdata.api.dto.CreateUomRequest;
import in.boss.masterdata.api.dto.ItemDto;
import in.boss.masterdata.api.dto.UpdateItemRequest;
import in.boss.masterdata.domain.ItemService;
import in.boss.masterdata.domain.LifecycleException;
import in.boss.masterdata.domain.UomService;
import in.boss.masterdata.persistence.ItemOperationalDependencies;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemOperationalDependenciesTest extends BaseMasterDataIntegrationTest {
    @Autowired ItemService items;
    @Autowired UomService uoms;
    @Autowired ItemOperationalDependencies dependencies;

    @Test
    void activeOperationsBlockBothPathsAndTerminalRecordsRemain() {
        UUID tenant = provisionTenant("item-operations");
        setTenantContext(tenant, "OWNER");
        UUID uom = uoms.createUom(CreateUomRequest.builder().name("Each").symbol("EA")
                .uomType("quantity").isBase(true).build()).getId();
        ItemDto item = items.createItem(CreateItemRequest.builder().code("ITEM").name("Item")
                .itemType("RAW_MATERIAL").uomId(uom).build());
        ItemDto finished = items.createItem(CreateItemRequest.builder().code("FINISHED").name("Finished")
                .itemType("FINISHED_GOOD").uomId(uom).build());
        UUID legal = insertId("INSERT INTO legal_entities (tenant_id, name, legal_name) VALUES (?, 'Legal', 'Legal') RETURNING id", tenant);
        UUID site = insertId("INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code) VALUES (?, ?, 'Site', 'S') RETURNING id", tenant, legal);
        UUID store = insertId("INSERT INTO stores (tenant_id, site_id, name, code) VALUES (?, ?, 'Store', 'S') RETURNING id", tenant, site);
        UUID customer = insertId("INSERT INTO customers (tenant_id, code, name) VALUES (?, 'C', 'Customer') RETURNING id", tenant);
        UUID supplier = insertId("INSERT INTO suppliers (tenant_id, code, name) VALUES (?, 'S', 'Supplier') RETURNING id", tenant);
        UUID user = UUID.randomUUID();
        UUID cpo = insertId("INSERT INTO cpos (tenant_id, cpo_number, customer_id, site_id, order_date, created_by) VALUES (?, 'C', ?, ?, CURRENT_DATE, ?) RETURNING id", tenant, customer, site, user);
        jdbc.update("INSERT INTO cpo_lines (tenant_id, cpo_id, line_no, item_id, quantity, uom_id, unit_price) VALUES (?, ?, 1, ?, 1, ?, 1)", tenant, cpo, item.getId(), uom);
        UUID pr = insertId("INSERT INTO purchase_requisitions (tenant_id, pr_number, site_id, requested_by) VALUES (?, 'P', ?, ?) RETURNING id", tenant, site, user);
        jdbc.update("INSERT INTO pr_lines (tenant_id, pr_id, line_no, item_id, quantity, uom_id) VALUES (?, ?, 1, ?, 1, ?)", tenant, pr, item.getId(), uom);
        UUID quote = insertId("INSERT INTO supplier_quotations (tenant_id, sq_number, pr_id, supplier_id, status, created_by) VALUES (?, 'Q', ?, ?, 'selected', ?) RETURNING id", tenant, pr, supplier, user);
        UUID spo = insertId("INSERT INTO spos (tenant_id, spo_number, sq_id, pr_id, supplier_id, site_id, status, order_date, created_by) VALUES (?, 'S', ?, ?, ?, ?, 'draft', CURRENT_DATE, ?) RETURNING id", tenant, quote, pr, supplier, site, user);
        jdbc.update("INSERT INTO spo_lines (tenant_id, spo_id, line_no, item_id, quantity, uom_id, unit_price) VALUES (?, ?, 1, ?, 1, ?, 1)", tenant, spo, item.getId(), uom);
        UUID job = insertId("INSERT INTO production_jobs (tenant_id, job_number, finished_item_id, site_id, quantity, uom_id, created_by) VALUES (?, 'J', ?, ?, 1, ?, ?) RETURNING id", tenant, item.getId(), site, uom, user);
        jdbc.update("INSERT INTO job_material_requirements (tenant_id, job_id, item_id, required_qty, uom_id) VALUES (?, ?, ?, 1, ?)", tenant, job, item.getId(), uom);
        UUID lot = insertId("INSERT INTO material_lots (tenant_id, item_id, store_id, quantity) VALUES (?, ?, ?, 1) RETURNING id", tenant, item.getId(), store);
        UUID reservation = insertId("INSERT INTO stock_reservation_records (tenant_id, lot_id, item_id, reserved_qty, reference_type, reference_id) VALUES (?, ?, ?, 1, 'other', ?) RETURNING id", tenant, lot, item.getId(), user);
        UUID dispatch = insertId("INSERT INTO dispatches (tenant_id, dispatch_number, customer_id, site_id, created_by) VALUES (?, 'D', ?, ?, ?) RETURNING id", tenant, customer, site, user);
        jdbc.update("INSERT INTO dispatch_lines (tenant_id, dispatch_id, item_id, quantity, uom_id, line_no) VALUES (?, ?, ?, 1, ?, 1)", tenant, dispatch, item.getId(), uom);

        assertThat(dependencies.findActive(tenant, item.getId())).containsExactly(
                "customer purchase order", "purchase requisition", "supplier purchase order",
                "production job", "stock on hand", "stock reservation", "dispatch");
        jdbc.update("UPDATE production_jobs SET finished_item_id = ? WHERE id = ?", finished.getId(), job);
        assertThat(dependencies.findActive(tenant, item.getId())).contains("production job");
        assertThat(dependencies.findActive(tenant, finished.getId())).containsExactly("production job");
        UUID otherTenant = provisionTenant("item-other");
        setTenantContext(tenant, "OWNER");
        assertThat(dependencies.findActive(otherTenant, item.getId())).isEmpty();
        assertThat(dependencies.findReferences(otherTenant, item.getId())).isEmpty();
        assertThatThrownBy(() -> items.deleteItem(item.getId()))
                .isInstanceOf(LifecycleException.class).hasMessageContaining("production job");
        assertThatThrownBy(() -> items.updateItem(item.getId(), UpdateItemRequest.builder().status("inactive").build()))
                .isInstanceOf(LifecycleException.class).hasMessageContaining("dispatch");

        jdbc.update("UPDATE cpos SET status = 'fulfilled' WHERE id = ?", cpo);
        jdbc.update("UPDATE purchase_requisitions SET status = 'cancelled' WHERE id = ?", pr);
        jdbc.update("UPDATE spos SET status = 'fully_received' WHERE id = ?", spo);
        jdbc.update("UPDATE production_jobs SET status = 'completed' WHERE id = ?", job);
        jdbc.update("UPDATE stock_reservation_records SET status = 'released' WHERE id = ?", reservation);
        jdbc.update("UPDATE material_lots SET quantity = 0 WHERE id = ?", lot);
        jdbc.update("UPDATE dispatches SET status = 'delivered' WHERE id = ?", dispatch);
        assertThat(dependencies.findActive(tenant, item.getId())).isEmpty();
        assertThat(dependencies.findReferences(tenant, item.getId())).contains(
                "production job", "inventory", "dispatch", "customer purchase order");
        assertThatThrownBy(() -> items.updateItem(item.getId(), UpdateItemRequest.builder().name("Renamed").build()))
                .isInstanceOf(LifecycleException.class).hasMessageContaining("production job");
        assertThatThrownBy(() -> items.updateItem(item.getId(), UpdateItemRequest.builder().description("Changed").build()))
                .isInstanceOf(LifecycleException.class).hasMessageContaining("inventory");
        assertThat(items.updateItem(item.getId(), UpdateItemRequest.builder().name("Item")
                .standardCost(new BigDecimal("2.50")).build()).getStandardCost()).isEqualByComparingTo("2.50");
        items.deleteItem(item.getId());
        assertThat(items.getItem(item.getId()).getStatus()).isEqualTo("inactive");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM production_jobs WHERE id = ?", Integer.class, job)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE production_jobs SET status = 'planned' WHERE id = ?", job))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("inactive");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO material_lots (tenant_id, item_id, store_id, quantity) VALUES (?, ?, ?, 1)",
                tenant, item.getId(), store))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("inactive");
    }

    @Test
    void productionWritesAndDeactivationSerializeUnderRls() throws Exception {
        UUID tenant = provisionTenant("item-locking");
        setTenantContext(tenant, "OWNER");
        UUID uom = uoms.createUom(CreateUomRequest.builder().name("Each").symbol("EA")
                .uomType("quantity").isBase(true).build()).getId();
        UUID first = items.createItem(CreateItemRequest.builder().code("ONE").name("One")
                .itemType("FINISHED_GOOD").uomId(uom).build()).getId();
        UUID second = items.createItem(CreateItemRequest.builder().code("TWO").name("Two")
                .itemType("FINISHED_GOOD").uomId(uom).build()).getId();
        UUID third = items.createItem(CreateItemRequest.builder().code("THREE").name("Three")
                .itemType("FINISHED_GOOD").uomId(uom).build()).getId();
        UUID legal = insertId("INSERT INTO legal_entities (tenant_id, name, legal_name) VALUES (?, 'Legal', 'Legal') RETURNING id", tenant);
        UUID site = insertId("INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code) VALUES (?, ?, 'Site', 'S') RETURNING id", tenant, legal);
        UUID user = UUID.randomUUID();

        jdbc.execute("CREATE ROLE item_lock_test LOGIN PASSWORD 'item_lock_test'");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO item_lock_test");
        jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO item_lock_test");
        try (Connection writer = appConnection(tenant); Connection lifecycle = appConnection(tenant)) {
            execute(writer, "INSERT INTO production_jobs (tenant_id, job_number, finished_item_id, site_id, quantity, uom_id, created_by) VALUES (?, 'J1', ?, ?, 1, ?, ?)", tenant, first, site, uom, user);
            execute(lifecycle, "SET LOCAL lock_timeout = '200ms'");
            assertThatThrownBy(() -> execute(lifecycle, "SELECT id FROM items WHERE tenant_id = ? AND id = ? FOR UPDATE", tenant, first))
                    .isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("55P03");
            lifecycle.rollback();
            writer.commit();
            assertThatThrownBy(() -> items.updateItem(first, UpdateItemRequest.builder().status("inactive").build()))
                    .isInstanceOf(LifecycleException.class).hasMessageContaining("production job");

            execute(lifecycle, "SELECT id FROM items WHERE tenant_id = ? AND id = ? FOR UPDATE", tenant, second);
            execute(lifecycle, "UPDATE items SET is_active = false WHERE tenant_id = ? AND id = ?", tenant, second);
            execute(writer, "SET LOCAL lock_timeout = '200ms'");
            assertThatThrownBy(() -> execute(writer, "INSERT INTO production_jobs (tenant_id, job_number, finished_item_id, site_id, quantity, uom_id, created_by) VALUES (?, 'J2', ?, ?, 1, ?, ?)", tenant, second, site, uom, user))
                    .isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("55P03");
            writer.rollback();
            lifecycle.commit();
            assertThatThrownBy(() -> execute(writer, "INSERT INTO production_jobs (tenant_id, job_number, finished_item_id, site_id, quantity, uom_id, created_by) VALUES (?, 'J2', ?, ?, 1, ?, ?)", tenant, second, site, uom, user))
                    .isInstanceOf(SQLException.class).hasMessageContaining("inactive");
            writer.rollback();
            UUID cancelled = insertId("INSERT INTO production_jobs (tenant_id, job_number, finished_item_id, site_id, quantity, uom_id, status, created_by) VALUES (?, 'J3', ?, ?, 1, ?, 'cancelled', ?) RETURNING id", tenant, first, site, uom, user);
            execute(writer, "INSERT INTO job_material_requirements (tenant_id, job_id, item_id, required_qty, uom_id) VALUES (?, ?, ?, 1, ?)", tenant, cancelled, second, uom);
            execute(lifecycle, "SET LOCAL lock_timeout = '200ms'");
            assertThatThrownBy(() -> execute(lifecycle, "UPDATE production_jobs SET status = 'planned' WHERE id = ?", cancelled))
                    .isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("55P03");
            lifecycle.rollback();
            writer.commit();
            assertThatThrownBy(() -> execute(lifecycle, "UPDATE production_jobs SET status = 'planned' WHERE id = ?", cancelled))
                    .isInstanceOf(SQLException.class).hasMessageContaining("inactive");
            lifecycle.rollback();

            execute(writer, "INSERT INTO production_jobs (tenant_id, job_number, finished_item_id, site_id, quantity, uom_id, status, created_by) VALUES (?, 'J4', ?, ?, 1, ?, 'cancelled', ?)", tenant, third, site, uom, user);
            execute(lifecycle, "SET LOCAL lock_timeout = '200ms'");
            assertThatThrownBy(() -> execute(lifecycle, "SELECT id FROM items WHERE tenant_id = ? AND id = ? FOR UPDATE", tenant, third))
                    .isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("55P03");
            lifecycle.rollback();
            writer.commit();
            assertThatThrownBy(() -> items.updateItem(third, UpdateItemRequest.builder().name("Renamed").build()))
                    .isInstanceOf(LifecycleException.class).hasMessageContaining("production job");
        }
    }

    private Connection appConnection(UUID tenant) throws SQLException {
        Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), "item_lock_test", "item_lock_test");
        connection.setAutoCommit(false);
        execute(connection, "SELECT set_config('app.current_tenant_id', ?, false)", tenant.toString());
        connection.commit();
        return connection;
    }

    private void execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.execute();
        }
    }

    private UUID insertId(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }
}
