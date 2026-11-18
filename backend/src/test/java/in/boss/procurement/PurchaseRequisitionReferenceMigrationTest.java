package in.boss.procurement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PurchaseRequisitionReferenceMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Test
    void preservesLegacyValuesAndEnforcesTenantBoundSnapshotPairs() throws Exception {
        flyway("100").migrate();

        UUID tenantA;
        UUID tenantB;
        UUID requisition;
        UUID legacyType = UUID.randomUUID();
        UUID optionA;
        UUID optionB;
        try (Connection connection = connect()) {
            UUID group = insert(connection,
                    "INSERT INTO customer_groups(name, slug) VALUES ('Migration group', 'reference-group') RETURNING id");
            tenantA = insert(connection,
                    "INSERT INTO tenant_registry(customer_group_id, name, slug, status) VALUES (?, 'A', 'reference-a', 'active') RETURNING id",
                    group);
            tenantB = insert(connection,
                    "INSERT INTO tenant_registry(customer_group_id, name, slug, status) VALUES (?, 'B', 'reference-b', 'active') RETURNING id",
                    group);
            UUID entity = insert(connection,
                    "INSERT INTO legal_entities(tenant_id, name, legal_name) VALUES (?, 'Entity', 'Entity') RETURNING id",
                    tenantA);
            UUID site = insert(connection,
                    "INSERT INTO physical_sites(tenant_id, legal_entity_id, name, code) VALUES (?, ?, 'Site', 'REF') RETURNING id",
                    tenantA, entity);
            requisition = insert(connection, """
                    INSERT INTO purchase_requisitions
                        (tenant_id, pr_number, site_id, requested_by, priority, pr_type_id, pr_type_label)
                    VALUES (?, 'PR-LEGACY', ?, ?, 'high', ?, 'Stores') RETURNING id
                    """, tenantA, site, UUID.randomUUID(), legacyType);
            optionA = insertOption(connection, tenantA, "A");
            optionB = insertOption(connection, tenantB, "B");
        }

        flyway(null).migrate();

        try (Connection connection = connect()) {
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT priority, pr_type_id, pr_type_label, priority_option_id,
                           priority_label_snapshot, purchase_type_option_id, purchase_type_label_snapshot
                    FROM purchase_requisitions WHERE id = ?
                    """)) {
                query.setObject(1, requisition);
                try (ResultSet row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("priority")).isEqualTo("high");
                    assertThat(row.getObject("pr_type_id", UUID.class)).isEqualTo(legacyType);
                    assertThat(row.getString("pr_type_label")).isEqualTo("Stores");
                    assertThat(row.getObject("priority_option_id")).isNull();
                    assertThat(row.getString("priority_label_snapshot")).isNull();
                    assertThat(row.getObject("purchase_type_option_id")).isNull();
                    assertThat(row.getString("purchase_type_label_snapshot")).isNull();
                }
            }

            assertThatThrownBy(() -> update(connection, """
                    UPDATE purchase_requisitions SET priority_option_id = ?, priority_label_snapshot = 'Foreign'
                    WHERE id = ?
                    """, optionB, requisition)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("pr_priority_option_tenant_fk");
            assertThatThrownBy(() -> update(connection, """
                    UPDATE purchase_requisitions SET purchase_type_option_id = ?, purchase_type_label_snapshot = 'Foreign'
                    WHERE id = ?
                    """, optionB, requisition)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("pr_purchase_type_option_tenant_fk");
            assertThatThrownBy(() -> update(connection,
                    "UPDATE purchase_requisitions SET priority_option_id = ? WHERE id = ?", optionA, requisition))
                    .isInstanceOf(SQLException.class).hasMessageContaining("pr_priority_snapshot_pair_check");
            assertThatThrownBy(() -> update(connection, """
                    UPDATE purchase_requisitions SET purchase_type_option_id = ?, purchase_type_label_snapshot = ' '
                    WHERE id = ?
                    """, optionA, requisition)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("pr_purchase_type_snapshot_pair_check");

            update(connection, """
                    UPDATE purchase_requisitions
                    SET priority_option_id = ?, priority_label_snapshot = 'Original',
                        purchase_type_option_id = ?, purchase_type_label_snapshot = 'Original type'
                    WHERE id = ?
                    """, optionA, optionA, requisition);
            update(connection, "UPDATE tenant_config_options SET label = 'Renamed' WHERE id = ?", optionA);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT priority_label_snapshot, purchase_type_label_snapshot FROM purchase_requisitions WHERE id = ?")) {
                query.setObject(1, requisition);
                try (ResultSet row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("Original");
                    assertThat(row.getString(2)).isEqualTo("Original type");
                }
            }
        }
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static UUID insertOption(Connection connection, UUID tenant, String code) throws SQLException {
        return insert(connection, """
                INSERT INTO tenant_config_options (tenant_id, category_id, code, label)
                SELECT ?, id, ?, ? FROM config_categories WHERE code = 'PAYMENT_MODES' RETURNING id
                """, tenant, code, code);
    }

    private static UUID insert(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Insert returned no id");
                return row.getObject(1, UUID.class);
            }
        }
    }

    private static void update(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
    }
}
