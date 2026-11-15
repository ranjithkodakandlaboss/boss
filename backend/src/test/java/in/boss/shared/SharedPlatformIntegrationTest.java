package in.boss.shared;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import in.boss.shared.config.ConfigOptionDto;
import in.boss.shared.config.ConfigService;
import in.boss.shared.config.IndustryTemplateService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import in.boss.shared.outbox.OutboxPublisher;
import in.boss.shared.tenant.TenantStatusCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the shared platform services (issues #20, #21, #90, #91, #96).
 * Runs Flyway from classpath:db/migration against a clean PostgreSQL 15 Testcontainers instance.
 *
 * Tests:
 * - Config options service returns tenant-scoped options (#90)
 * - Industry template seeds correct options for all 6 approved industry types (#91)
 * - Config options are isolated between tenants (RLS)
 * - TenantStatusCache returns correct status, fails closed on unknown/DB error (#21)
 * - Outbox write is transactional — rollback proof
 * - Connection pool reuse maintains correct tenant isolation
 */
@Testcontainers
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SharedPlatformIntegrationTest {

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
    @Autowired ConfigService configService;
    @Autowired IndustryTemplateService industryTemplateService;
    @Autowired TenantStatusCache tenantStatusCache;
    @Autowired OutboxPublisher outboxPublisher;
    @Autowired PlatformTransactionManager txManager;

    static UUID tenantA;
    static UUID tenantB;

    @BeforeAll
    static void provisionTenants(@Autowired JdbcTemplate jdbc) {
        tenantA = provisionTenant(jdbc, "platform-test-a");
        tenantB = provisionTenant(jdbc, "platform-test-b");
    }

    @AfterEach
    void cleanup() {
        BossContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    // ---- Config options (#90) ----

    @Test
    void configService_returnsOptionsForCurrentTenant() {
        setContext(tenantA, "OWNER");
        industryTemplateService.applyTemplate(tenantA, "FABRICATION_STEEL");

        List<ConfigOptionDto> options = configService.getOptions("ITEM_CATEGORY");

        assertThat(options).isNotEmpty();
        assertThat(options).allMatch(o -> o.categoryCode().equals("ITEM_CATEGORY"));
        assertThat(options).allMatch(ConfigOptionDto::isSystem);
    }

    @Test
    void configService_isolation_tenantBCannotSeeTenantAOptions() {
        // Seed tenant A with PRECISION_MACHINING (has codes like BAR_STOCK, CNC_COMPONENT)
        industryTemplateService.applyTemplate(tenantA, "PRECISION_MACHINING");

        // Tenant B gets no template applied for this category
        setContext(tenantB, "OWNER");
        List<ConfigOptionDto> optionsB = configService.getOptions("ITEM_CATEGORY");

        // Codes unique to PRECISION_MACHINING must not appear for tenant B
        List<String> codesB = optionsB.stream().map(ConfigOptionDto::code).toList();
        assertThat(codesB).doesNotContain("BAR_STOCK", "CNC_COMPONENT", "FORGING_RM");
    }

    @Test
    void configService_cacheEvictedOnWrite() {
        setContext(tenantA, "OWNER");
        int before = configService.getOptions("PAYMENT_MODES").size();

        setContextWithSecurity(tenantA, "OWNER");
        configService.createOption("PAYMENT_MODES", "CRYPTO", "Cryptocurrency", 99);

        int after = configService.getOptions("PAYMENT_MODES").size();
        assertThat(after).isEqualTo(before + 1);
    }

    // ---- Industry templates (#91) ----

    @Test
    void industryTemplate_allSixApprovedTypesCanBeApplied() {
        for (String industryType : IndustryTemplateService.SUPPORTED_INDUSTRY_TYPES) {
            UUID tenant = provisionTenant(jdbc, "ind-" + industryType.toLowerCase().replace("_", "-"));
            industryTemplateService.applyTemplate(tenant, industryType);

            setContext(tenant, "OWNER");
            List<ConfigOptionDto> itemCats    = configService.getOptions("ITEM_CATEGORY");
            List<ConfigOptionDto> stages      = configService.getOptions("PRODUCTION_STAGES");
            List<ConfigOptionDto> inspTypes   = configService.getOptions("INSPECTION_TYPES");
            List<ConfigOptionDto> rejReasons  = configService.getOptions("REJECTION_REASONS");

            assertThat(itemCats).as("ITEM_CATEGORY for %s", industryType).isNotEmpty();
            assertThat(stages).as("PRODUCTION_STAGES for %s", industryType).isNotEmpty();
            assertThat(inspTypes).as("INSPECTION_TYPES for %s", industryType).isNotEmpty();
            assertThat(rejReasons).as("REJECTION_REASONS for %s", industryType).isNotEmpty();
        }
    }

    @Test
    void industryTemplate_applyTemplate_isIdempotent() {
        UUID tenant = provisionTenant(jdbc, "idempotent-test");
        industryTemplateService.applyTemplate(tenant, "CASTING");
        industryTemplateService.applyTemplate(tenant, "CASTING"); // second apply must not duplicate

        setContext(tenant, "OWNER");
        List<ConfigOptionDto> options = configService.getOptions("ITEM_CATEGORY");
        long distinct = options.stream().map(ConfigOptionDto::code).distinct().count();
        assertThat(distinct).isEqualTo(options.size());
    }

    @Test
    void industryTemplate_preservesTenantCustomisation() {
        UUID tenant = provisionTenant(jdbc, "customised-test");
        industryTemplateService.applyTemplate(tenant, "FORGING");

        // Tenant creates a custom option (not in template)
        setContextWithSecurity(tenant, "OWNER");
        configService.createOption("ITEM_CATEGORY", "CUSTOM_BILLET", "Special Billet", 99);

        // Re-applying template must not remove the custom option
        industryTemplateService.applyTemplate(tenant, "FORGING");

        setContext(tenant, "OWNER");
        List<String> codes = configService.getOptions("ITEM_CATEGORY")
                .stream().map(ConfigOptionDto::code).toList();
        assertThat(codes).contains("CUSTOM_BILLET");
    }

    @Test
    void industryTemplate_seedStandardUoms_populatesUoms() {
        UUID tenant = provisionTenant(jdbc, "uom-test");
        industryTemplateService.seedStandardUoms(tenant);

        int count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM units_of_measure WHERE tenant_id = ?",
                Integer.class, tenant);
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void industryTemplate_unknownType_throwsIllegalArgument() {
        UUID tenant = provisionTenant(jdbc, "bad-type");
        assertThatThrownBy(() -> industryTemplateService.applyTemplate(tenant, "aerospace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aerospace");
    }

    // ---- Tenant status cache (#21) ----

    @Test
    void tenantStatusCache_activeReturnsActive() {
        assertThat(tenantStatusCache.getStatus(tenantA)).isEqualTo("active");
    }

    @Test
    void tenantStatusCache_suspendedTenantReturnsSuspended() {
        UUID suspended = provisionTenant(jdbc, "suspended-tenant");
        jdbc.update("UPDATE tenant_registry SET status = 'suspended' WHERE id = ?", suspended);
        tenantStatusCache.evict(suspended);

        assertThat(tenantStatusCache.getStatus(suspended)).isEqualTo("suspended");
    }

    @Test
    void tenantStatusCache_evictForcesFreshLoad() {
        tenantStatusCache.getStatus(tenantA);
        tenantStatusCache.evict(tenantA);
        assertThat(tenantStatusCache.getStatus(tenantA)).isEqualTo("active");
    }

    @Test
    void tenantStatusCache_unknownTenantId_returnsUnknown() {
        UUID nonexistent = UUID.randomUUID(); // not in tenant_registry
        assertThat(tenantStatusCache.getStatus(nonexistent))
                .isEqualTo(TenantStatusCache.STATUS_UNKNOWN);
    }

    // ---- Outbox transactional behaviour ----

    @Test
    void outbox_writtenInSameTransaction_visibleAfterCommit() {
        setContext(tenantA, "OWNER");
        UUID entityId = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO outbox_events (tenant_id, aggregate_type, aggregate_id, event_type, payload) "
                + "VALUES (?, 'TEST_ENTITY', ?, 'TEST_CREATED', '{}'::jsonb)",
                tenantA, entityId);

        int count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?",
                Integer.class, entityId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void outbox_eventDoesNotSurviveRolledBackTransaction() {
        setContext(tenantA, "OWNER");
        UUID entityId = UUID.randomUUID();
        AtomicReference<Exception> caught = new AtomicReference<>();

        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.execute(status -> {
                outboxPublisher.publish("TEST_ENTITY", entityId, "TEST_CREATED", "{}");
                status.setRollbackOnly();
                return null;
            });
        } catch (Exception e) {
            caught.set(e);
        }

        // Even if the tx manager threw on rollback, the outbox row must NOT exist
        int count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?",
                Integer.class, entityId);
        assertThat(count)
                .as("Outbox event must not survive a rolled-back transaction")
                .isEqualTo(0);
    }

    // ---- Connection pool reuse across tenant contexts ----

    @Test
    void connectionPool_isolatesTenantContextAcrossReuse() {
        UUID t1 = provisionTenant(jdbc, "pool-reuse-t1");
        UUID t2 = provisionTenant(jdbc, "pool-reuse-t2");
        industryTemplateService.applyTemplate(t1, "ELECTRONICS_ASSEMBLY");
        industryTemplateService.applyTemplate(t2, "GENERAL_MANUFACTURING");

        setContext(t1, "OWNER");
        List<String> t1Codes = configService.getOptions("ITEM_CATEGORY")
                .stream().map(ConfigOptionDto::code).toList();

        setContext(t2, "OWNER");
        List<String> t2Codes = configService.getOptions("ITEM_CATEGORY")
                .stream().map(ConfigOptionDto::code).toList();

        // t1 has ELECTRONICS_ASSEMBLY codes; t2 has GENERAL_MANUFACTURING codes
        assertThat(t1Codes).contains("PCB", "COMPONENTS");
        assertThat(t2Codes).contains("RAW_MATERIAL", "FINISHED");

        // No cross-tenant leakage (codes unique to each type must not appear in the other)
        assertThat(t1Codes).doesNotContain("RAW_MATERIAL");
        assertThat(t2Codes).doesNotContain("PCB");
    }

    @Test
    void connectionPool_noContextQuery_isRejectedAtServiceLayer() {
        UUID tenant = provisionTenant(jdbc, "no-ctx-test");
        industryTemplateService.applyTemplate(tenant, "CASTING");

        // Clear context — service must fail-closed (application-layer guard before RLS)
        BossContextHolder.clear();
        assertThatThrownBy(() -> configService.getOptions("ITEM_CATEGORY"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BossContext required");
    }

    // ---- Helpers ----

    private void setContext(UUID tenantId, String role) {
        BossContextHolder.set(new BossContext(tenantId, UUID.randomUUID(),
                Set.of(role), null, null));
    }

    private void setContextWithSecurity(UUID tenantId, String role) {
        setContext(tenantId, role);
        var auth = new UsernamePasswordAuthenticationToken("test-user", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    static UUID provisionTenant(JdbcTemplate jdbc, String slug) {
        UUID groupId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO customer_groups (name, slug) VALUES (?, ?) "
                + "ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name "
                + "RETURNING id::text",
                String.class, slug + " Group", slug + "-grp"));
        return UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_registry (customer_group_id, name, slug, status) "
                + "VALUES (?, ?, ?, 'active') "
                + "ON CONFLICT (slug) DO UPDATE SET status = 'active' "
                + "RETURNING id::text",
                String.class, groupId, slug + " Tenant", slug));
    }
}
