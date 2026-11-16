package in.boss.shared.inventory;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests verifying that stock_movements is append-only (issue #18).
 *
 * V016 creates stock_movements with no updated_at (append-only by design).
 * V050 adds the blocking triggers:
 *   trg_stock_movements_no_update — blocks UPDATE
 *   trg_stock_movements_no_delete — blocks DELETE
 */
@Testcontainers
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class StockMovementAppendOnlyTest {

    @MockBean FirebaseApp firebaseApp;
    @MockBean FirebaseAuth firebaseAuth;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("boss_test")
            .withUsername("boss")
            .withPassword("boss");

    @DynamicPropertySource
    static void configureDs(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    static UUID tenantId;
    static UUID itemId;

    @BeforeAll
    static void provisionData(@Autowired JdbcTemplate jdbc) {
        UUID groupId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO customer_groups (name, slug) VALUES ('Stock Group','stock-grp') "
                + "ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name RETURNING id::text",
                String.class));
        tenantId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_registry (customer_group_id, name, slug, status) "
                + "VALUES (?, 'Stock Tenant', 'stock-tenant', 'active') "
                + "ON CONFLICT (slug) DO UPDATE SET status = 'active' RETURNING id::text",
                String.class, groupId));

        UUID uomId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO units_of_measure (tenant_id, name, symbol, uom_type, is_base) "
                + "VALUES (?, 'Piece', 'PCS', 'quantity', true) RETURNING id::text",
                String.class, tenantId));

        itemId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO items (tenant_id, code, name, item_type, base_uom_id) "
                + "VALUES (?, 'TEST-ITEM-001', 'Test Item', 'raw_material', ?) RETURNING id::text",
                String.class, tenantId, uomId));
    }

    @AfterEach
    void clearContext() {
        BossContextHolder.clear();
    }

    private UUID insertMovement(String movementType, double qty) {
        return UUID.fromString(jdbc.queryForObject(
                "INSERT INTO stock_movements (tenant_id, movement_type, item_id, quantity) "
                + "VALUES (?, ?, ?, ?) RETURNING id::text",
                String.class, tenantId, movementType, itemId, qty));
    }

    @Test
    void insert_stockMovement_succeeds() {
        UUID movId = insertMovement("receipt", 100.0);

        int count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE id = ?", Integer.class, movId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void update_stockMovement_throwsTriggerException() {
        UUID movId = insertMovement("receipt", 50.0);

        assertThatThrownBy(() ->
                jdbc.update("UPDATE stock_movements SET quantity = 999 WHERE id = ?", movId))
                .hasMessageContaining("append-only");
    }

    @Test
    void delete_stockMovement_throwsTriggerException() {
        UUID movId = insertMovement("scrap", 10.0);

        assertThatThrownBy(() ->
                jdbc.update("DELETE FROM stock_movements WHERE id = ?", movId))
                .hasMessageContaining("append-only");
    }

    @Test
    void multipleMovementTypes_allInsertSuccessfully() {
        // All valid movement_type values must be insertable
        String[] types = {"receipt", "issue", "return", "transfer_out", "transfer_in",
                          "adjustment_add", "adjustment_reduce", "opening_balance", "scrap", "consume"};

        for (String type : types) {
            UUID movId = insertMovement(type, 1.0);
            int count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM stock_movements WHERE id = ?", Integer.class, movId);
            assertThat(count).as("movement_type %s should insert successfully", type).isEqualTo(1);
        }
    }
}
