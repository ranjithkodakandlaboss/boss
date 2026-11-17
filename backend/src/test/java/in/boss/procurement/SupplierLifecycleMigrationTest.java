package in.boss.procurement;

import in.boss.masterdata.BaseMasterDataIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class SupplierLifecycleMigrationTest {

    @Test
    void reconcilesKnownLegacyValuesAndBackfillsOnlyUnambiguousSelectedQuotationLinks() throws Exception {
        String schema = "supplier_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
        String url = BaseMasterDataIntegrationTest.postgres.getJdbcUrl();
        String user = BaseMasterDataIntegrationTest.postgres.getUsername();
        String password = BaseMasterDataIntegrationTest.postgres.getPassword();

        try {
            createSchema(url, user, password, schema);
            Flyway beforeReconciliation = flyway(url, user, password, schema, "070");
            beforeReconciliation.migrate();

            Fixture fixture = insertFixture(url, user, password, schema);
            Flyway reconciliation = flyway(url, user, password, schema, null);

            Throwable unsupportedStateFailure = catchThrowable(reconciliation::migrate);
            assertThat(rootCause(unsupportedStateFailure))
                    .hasMessageContaining("draft/expired quotations and closed SPOs");
            assertLegacyStateUnchanged(url, user, password, schema, fixture);

            deleteUnmappableRows(url, user, password, schema, fixture);
            reconciliation.repair();

            Throwable missingLinkFailure = catchThrowable(reconciliation::migrate);
            assertThat(rootCause(missingLinkFailure))
                    .hasMessageContaining("SPOs without quotation");
            assertLegacyStateUnchanged(url, user, password, schema, fixture);

            deleteUnresolvedSpo(url, user, password, schema, fixture);
            reconciliation.repair();
            reconciliation.migrate();

            assertThat(readStatus(url, user, password, schema, "supplier_quotations", fixture.sentQuoteId()))
                    .isEqualTo("requested");
            assertThat(readStatus(url, user, password, schema, "supplier_quotations", fixture.receivedQuoteId()))
                    .isEqualTo("received");
            assertThat(readStatus(url, user, password, schema, "supplier_quotations", fixture.acceptedQuoteId()))
                    .isEqualTo("selected");
            assertThat(readStatus(url, user, password, schema, "supplier_quotations", fixture.rejectedQuoteId()))
                    .isEqualTo("rejected");
            assertThat(readStatus(url, user, password, schema, "spos", fixture.openSpoId()))
                    .isEqualTo("issued");
            assertThat(readStatus(url, user, password, schema, "spos", fixture.partialSpoId()))
                    .isEqualTo("partially_received");
            assertThat(readStatus(url, user, password, schema, "spos", fixture.fulfilledSpoId()))
                    .isEqualTo("fully_received");
            assertThat(readStatus(url, user, password, schema, "spos", fixture.cancelledSpoId()))
                    .isEqualTo("cancelled");
            assertBackfilledLink(url, user, password, schema, fixture);
            assertNotNullAndTenantSafeSchema(url, user, password, schema);
        } finally {
            dropSchema(url, user, password, schema);
        }
    }

    private static Flyway flyway(String url, String user, String password, String schema, String target) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(target == null ? org.flywaydb.core.api.MigrationVersion.LATEST : org.flywaydb.core.api.MigrationVersion.fromVersion(target))
                .load();
    }

    private static void createSchema(String url, String user, String password, String schema) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        assertThat(throwable).isNotNull();
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Fixture insertFixture(String url, String user, String password, String schema) throws SQLException {
        try (Connection connection = connection(url, user, password, schema)) {
            UUID groupId = insert(connection,
                    "INSERT INTO customer_groups(name, slug) VALUES (?, ?) RETURNING id",
                    "Migration Test", "migration-" + UUID.randomUUID());
            UUID tenantId = insert(connection,
                    "INSERT INTO tenant_registry(customer_group_id, name, slug, status) VALUES (?, ?, ?, 'active') RETURNING id",
                    groupId, "Migration Tenant", "tenant-" + UUID.randomUUID());
            UUID legalEntityId = insert(connection,
                    "INSERT INTO legal_entities(tenant_id, name, legal_name) VALUES (?, ?, ?) RETURNING id",
                    tenantId, "Migration Entity", "Migration Entity Ltd");
            UUID siteId = insert(connection,
                    "INSERT INTO physical_sites(tenant_id, legal_entity_id, name, code) VALUES (?, ?, ?, ?) RETURNING id",
                    tenantId, legalEntityId, "Migration Site", "SITE-" + UUID.randomUUID());
            UUID supplierId = insert(connection,
                    "INSERT INTO suppliers(tenant_id, code, name) VALUES (?, ?, ?) RETURNING id",
                    tenantId, "SUP-" + UUID.randomUUID(), "Migration Supplier");
            UUID uomId = insert(connection,
                    "INSERT INTO units_of_measure(tenant_id, name, symbol, is_base) VALUES (?, ?, ?, TRUE) RETURNING id",
                    tenantId, "Each", "ea-" + UUID.randomUUID());
            UUID itemId = insert(connection,
                    "INSERT INTO items(tenant_id, code, name, item_type, base_uom_id) VALUES (?, ?, ?, 'raw_material', ?) RETURNING id",
                    tenantId, "ITEM-" + UUID.randomUUID(), "Migration Item", uomId);
            UUID actorId = UUID.randomUUID();
            UUID prId = insert(connection,
                    "INSERT INTO purchase_requisitions(tenant_id, pr_number, site_id, status, requested_by) VALUES (?, ?, ?, 'approved', ?) RETURNING id",
                    tenantId, "PR-" + UUID.randomUUID(), siteId, actorId);
            UUID prLineId = insert(connection,
                    "INSERT INTO pr_lines(tenant_id, pr_id, line_no, item_id, quantity, uom_id) VALUES (?, ?, 1, ?, 5, ?) RETURNING id",
                    tenantId, prId, itemId, uomId);

            UUID sentQuoteId = insertQuote(connection, tenantId, supplierId, prId, actorId, "sent");
            UUID receivedQuoteId = insertQuote(connection, tenantId, supplierId, prId, actorId, "received");
            UUID acceptedQuoteId = insertQuote(connection, tenantId, supplierId, prId, actorId, "accepted");
            UUID rejectedQuoteId = insertQuote(connection, tenantId, supplierId, prId, actorId, "rejected");
            UUID expiredQuoteId = insertQuote(connection, tenantId, supplierId, prId, actorId, "expired");
            UUID quoteLineId = insert(connection,
                    "INSERT INTO supplier_quotation_lines(tenant_id, sq_id, pr_line_id, line_no, item_id, quantity, uom_id, unit_price) VALUES (?, ?, ?, 1, ?, 5, ?, 12.50) RETURNING id",
                    tenantId, acceptedQuoteId, prLineId, itemId, uomId);

            UUID openSpoId = insertSpo(connection, tenantId, supplierId, siteId, prId, actorId, "open", null);
            insert(connection,
                    "INSERT INTO spo_lines(tenant_id, spo_id, sq_line_id, pr_line_id, line_no, item_id, quantity, uom_id, unit_price) VALUES (?, ?, ?, ?, 1, ?, 5, ?, 12.50) RETURNING id",
                    tenantId, openSpoId, quoteLineId, prLineId, itemId, uomId);
            UUID partialSpoId = insertSpo(connection, tenantId, supplierId, siteId, prId, actorId, "partial", acceptedQuoteId);
            UUID fulfilledSpoId = insertSpo(connection, tenantId, supplierId, siteId, prId, actorId, "fulfilled", acceptedQuoteId);
            UUID cancelledSpoId = insertSpo(connection, tenantId, supplierId, siteId, prId, actorId, "cancelled", acceptedQuoteId);
            UUID closedSpoId = insertSpo(connection, tenantId, supplierId, siteId, prId, actorId, "closed", acceptedQuoteId);
            UUID unresolvedSpoId = insertSpo(connection, tenantId, supplierId, siteId, prId, actorId, "open", null);
            return new Fixture(tenantId, prId, sentQuoteId, receivedQuoteId, acceptedQuoteId,
                    rejectedQuoteId, expiredQuoteId, openSpoId, partialSpoId, fulfilledSpoId,
                    cancelledSpoId, closedSpoId, unresolvedSpoId);
        }
    }

    private static UUID insertQuote(Connection connection, UUID tenantId, UUID supplierId,
                                    UUID prId, UUID actorId, String status) throws SQLException {
        return insert(connection,
                "INSERT INTO supplier_quotations(tenant_id, sq_number, pr_id, supplier_id, status, created_by) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                tenantId, "SQ-" + UUID.randomUUID(), prId, supplierId, status, actorId);
    }

    private static UUID insertSpo(Connection connection, UUID tenantId, UUID supplierId,
                                  UUID siteId, UUID prId, UUID actorId, String status,
                                  UUID quoteId) throws SQLException {
        return insert(connection,
                "INSERT INTO spos(tenant_id, spo_number, sq_id, pr_id, supplier_id, site_id, status, order_date, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_DATE, ?) RETURNING id",
                tenantId, "SPO-" + UUID.randomUUID(), quoteId, quoteId == null ? null : prId,
                supplierId, siteId, status, actorId);
    }

    private static UUID insert(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private static Connection connection(String url, String user, String password, String schema) throws SQLException {
        Connection connection = DriverManager.getConnection(url, user, password);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
        }
        return connection;
    }

    private static void deleteUnmappableRows(String url, String user, String password, String schema,
                                             Fixture fixture) throws SQLException {
        try (Connection connection = connection(url, user, password, schema);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM spos WHERE id IN ('" + fixture.closedSpoId()
                    + "')");
            statement.executeUpdate("DELETE FROM supplier_quotations WHERE id = '" + fixture.expiredQuoteId() + "'");
        }
    }

    private static void deleteUnresolvedSpo(String url, String user, String password, String schema,
                                            Fixture fixture) throws SQLException {
        try (Connection connection = connection(url, user, password, schema);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM spos WHERE id = '" + fixture.unresolvedSpoId() + "'");
        }
    }

    private static void assertLegacyStateUnchanged(String url, String user, String password, String schema,
                                                   Fixture fixture) throws SQLException {
        assertThat(readStatus(url, user, password, schema, "supplier_quotations", fixture.acceptedQuoteId()))
                .isEqualTo("accepted");
        assertThat(readStatus(url, user, password, schema, "spos", fixture.openSpoId()))
                .isEqualTo("open");
        assertThat(readSpoQuoteId(url, user, password, schema, fixture.openSpoId())).isNull();
    }

    private static String readStatus(String url, String user, String password, String schema,
                                     String table, UUID id) throws SQLException {
        try (Connection connection = connection(url, user, password, schema);
             PreparedStatement statement = connection.prepareStatement("SELECT status FROM " + table + " WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static UUID readSpoQuoteId(String url, String user, String password, String schema,
                                       UUID spoId) throws SQLException {
        try (Connection connection = connection(url, user, password, schema);
             PreparedStatement statement = connection.prepareStatement("SELECT sq_id FROM spos WHERE id = ?")) {
            statement.setObject(1, spoId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private static void assertBackfilledLink(String url, String user, String password, String schema,
                                             Fixture fixture) throws SQLException {
        try (Connection connection = connection(url, user, password, schema);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT sq_id, pr_id FROM spos WHERE id = ?")) {
            statement.setObject(1, fixture.openSpoId());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                assertThat(result.getObject("sq_id", UUID.class)).isEqualTo(fixture.acceptedQuoteId());
                assertThat(result.getObject("pr_id", UUID.class)).isEqualTo(fixture.prId());
            }
        }
    }

    private static void assertNotNullAndTenantSafeSchema(String url, String user, String password,
                                                         String schema) throws SQLException {
        try (Connection connection = connection(url, user, password, schema);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT column_name, is_nullable
                     FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name = 'spos'
                       AND column_name IN ('sq_id', 'pr_id')
                     ORDER BY column_name
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("column_name")).isEqualTo("pr_id");
            assertThat(result.getString("is_nullable")).isEqualTo("NO");
            assertThat(result.next()).isTrue();
            assertThat(result.getString("column_name")).isEqualTo("sq_id");
            assertThat(result.getString("is_nullable")).isEqualTo("NO");
            assertThat(result.next()).isFalse();
        }

        try (Connection connection = connection(url, user, password, schema);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT relname, relrowsecurity, relforcerowsecurity
                     FROM pg_class
                     WHERE relnamespace = current_schema()::regnamespace
                       AND relname IN ('supplier_quotations', 'supplier_quotation_lines', 'spos', 'spo_lines')
                     ORDER BY relname
                     """)) {
            int tableCount = 0;
            while (result.next()) {
                assertThat(result.getBoolean("relrowsecurity")).isTrue();
                assertThat(result.getBoolean("relforcerowsecurity")).isTrue();
                tableCount++;
            }
            assertThat(tableCount).isEqualTo(4);
        }
    }

    private static void dropSchema(String url, String user, String password, String schema) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private record Fixture(UUID tenantId, UUID prId, UUID sentQuoteId, UUID receivedQuoteId,
                           UUID acceptedQuoteId, UUID rejectedQuoteId, UUID expiredQuoteId,
                           UUID openSpoId, UUID partialSpoId, UUID fulfilledSpoId,
                           UUID cancelledSpoId, UUID closedSpoId, UUID unresolvedSpoId) {
    }
}
