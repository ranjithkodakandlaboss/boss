package in.boss.shared.config;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CI-gated test verifying Row-Level Security on tenant_config_options and
 * authoritative routing for ITEM_CATEGORY (issues #13, #90, #98 and DoD).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ConfigOptionsRlsTest {

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

    static UUID tenantA;
    static UUID tenantB;

    @BeforeAll
    static void provisionData(@Autowired JdbcTemplate jdbc) {
        UUID groupId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO customer_groups (name, slug) VALUES ('Config RLS Group','cfg-rls-grp') "
                + "ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name RETURNING id::text",
                String.class));

        tenantA = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_registry (customer_group_id, name, slug, status) "
                + "VALUES (?, 'Config Tenant A', 'cfg-tenant-a', 'active') "
                + "ON CONFLICT (slug) DO UPDATE SET status = 'active' RETURNING id::text",
                String.class, groupId));

        tenantB = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_registry (customer_group_id, name, slug, status) "
                + "VALUES (?, 'Config Tenant B', 'cfg-tenant-b', 'active') "
                + "ON CONFLICT (slug) DO UPDATE SET status = 'active' RETURNING id::text",
                String.class, groupId));
    }

    @AfterEach
    void clear() {
        BossContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private void setTenant(UUID tenantId) {
        setTenant(tenantId, Set.of("OWNER"));
    }

    private void setTenant(UUID tenantId, Set<String> roles) {
        String firebaseUid = "uid-" + tenantId + "-" + UUID.randomUUID();
        BossContextHolder.set(new BossContext(tenantId, null, roles, null, firebaseUid));
        UUID userId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO app_users (tenant_id, firebase_uid, email, display_name) "
                        + "VALUES (?, ?, ?, 'Config test user') RETURNING id::text",
                String.class, tenantId, firebaseUid, firebaseUid + "@example.test"));
        BossContextHolder.set(new BossContext(tenantId, userId, roles, null, firebaseUid));
        var auth = new UsernamePasswordAuthenticationToken(
                firebaseUid, null, roles.stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void tenantIsolation_tenantBCannotReadTenantAOptions() {
        // Tenant A has custom options in tenant_config_options
        setTenant(tenantA);
        configService.createOption("PAYMENT_MODES", "TENANT_A_PAY", "Tenant A Mode", 1);

        List<ConfigOptionDto> optsA = configService.getOptions("PAYMENT_MODES");
        assertThat(optsA).anyMatch(o -> "TENANT_A_PAY".equals(o.code()));

        // Tenant B must not see Tenant A's options
        setTenant(tenantB);
        List<ConfigOptionDto> optsB = configService.getOptions("PAYMENT_MODES");
        assertThat(optsB).noneMatch(o -> "TENANT_A_PAY".equals(o.code()));
    }

    @Test
    void itemCategory_authoritativeRoutingToItemCategoriesTable() {
        setTenant(tenantA);
        industryTemplateService.seedTenantDefaults(tenantA, "FABRICATION_STEEL");

        // 1. Reading ITEM_CATEGORY options returns seeded item_categories
        List<ConfigOptionDto> categories = configService.getOptions("ITEM_CATEGORY");
        assertThat(categories).isNotEmpty();
        assertThat(categories).anyMatch(c -> "HR_PLATE".equals(c.code()));
        assertThat(categories).allMatch(ConfigOptionDto::isSystem);

        // 2. Confirm zero rows exist in tenant_config_options for category_code=ITEM_CATEGORY
        Integer configOptionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_config_options o "
                + "JOIN config_categories cc ON cc.id = o.category_id "
                + "WHERE o.tenant_id = ? AND cc.code = 'ITEM_CATEGORY'",
                Integer.class,
                tenantA);
        assertThat(configOptionCount).isEqualTo(0);

        // 3. Creating a new category via configService routes to item_categories
        configService.createOption("ITEM_CATEGORY", "CUSTOM_RAW", "Custom Raw Material", 10);
        List<ConfigOptionDto> updated = configService.getOptions("ITEM_CATEGORY");
        assertThat(updated).anyMatch(c -> "CUSTOM_RAW".equals(c.code()));

        // Still zero rows in tenant_config_options
        Integer countAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_config_options o "
                + "JOIN config_categories cc ON cc.id = o.category_id "
                + "WHERE o.tenant_id = ? AND cc.code = 'ITEM_CATEGORY'",
                Integer.class,
                tenantA);
        assertThat(countAfter).isEqualTo(0);

        // Stored in item_categories table
        Integer itemCatCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM item_categories WHERE tenant_id = ? AND code = 'CUSTOM_RAW'",
                Integer.class,
                tenantA);
        assertThat(itemCatCount).isEqualTo(1);
    }

    @Test
    void tenantOptionsCanBeEditedReorderedAndDeactivatedSafely() {
        setTenant(tenantA);
        ConfigOptionDto first = configService.createOption("PAYMENT_MODES", "CUSTOM_CARD", "Card", 10);
        ConfigOptionDto second = configService.createOption("PAYMENT_MODES", "CUSTOM_WIRE", "Wire", 20);

        List<UUID> reordered = new java.util.ArrayList<>(configService.getOptions("PAYMENT_MODES").stream()
                .filter(option -> !option.isSystem()).map(ConfigOptionDto::id).toList());
        reordered.remove(first.id());
        reordered.remove(second.id());
        reordered.add(second.id());
        reordered.add(first.id());
        configService.reorderOptions("PAYMENT_MODES", reordered);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_config_option_history
                WHERE tenant_id = ? AND option_id = ? AND change_type = 'REORDERED'
                  AND old_sort_order = 10 AND new_sort_order > old_sort_order
                """, Integer.class, tenantA, first.id())).isEqualTo(1);
        configService.updateOption("PAYMENT_MODES", first.id(), "Card payment");
        configService.setOptionActive("PAYMENT_MODES", second.id(), false);

        List<ConfigOptionDto> options = configService.getOptions("PAYMENT_MODES", true);
        assertThat(options).filteredOn(option -> option.id().equals(first.id())).singleElement()
                .extracting(ConfigOptionDto::label).isEqualTo("Card payment");
        assertThat(options).filteredOn(option -> option.id().equals(second.id())).singleElement()
                .extracting(ConfigOptionDto::isActive).isEqualTo(false);
        assertThat(options.stream().filter(option -> !option.isSystem()).map(ConfigOptionDto::id).toList())
                .containsExactlyElementsOf(reordered);
    }

    @Test
    void dispatchRolesCanCreateOnlyDispatchOptionsAndCannotEditLabels() {
        jdbc.update("""
                INSERT INTO config_categories (code, name, scope) VALUES
                  ('CARRIER', 'Carriers', 'tenant'),
                  ('VEHICLE', 'Vehicles', 'tenant')
                ON CONFLICT (code) DO NOTHING
                """);

        setTenant(tenantA, Set.of("OPS_MANAGER"));
        ConfigOptionDto carrier = configService.createOption("CARRIER", "LOCAL_CARRIER", "Local carrier", 10);
        assertThat(carrier.label()).isEqualTo("Local carrier");
        assertThatThrownBy(() -> configService.createOption("PAYMENT_MODES",
                "UNAUTHORIZED_MODE", "Unauthorized mode", 10))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Only owners");
        assertThatThrownBy(() -> configService.updateOption("CARRIER", carrier.id(), "Renamed carrier"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Only owners");
        assertThatThrownBy(() -> configService.setOptionActive("CARRIER", carrier.id(), false))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Only owners");
        assertThatThrownBy(() -> configService.reorderOptions("CARRIER", List.of(carrier.id())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Only owners");

        setTenant(tenantB, Set.of("STORE_KEEPER"));
        ConfigOptionDto vehicle = configService.createOption("VEHICLE", "LOCAL_VEHICLE", "Local vehicle", 10);
        assertThat(vehicle.label()).isEqualTo("Local vehicle");
    }

    @Test
    void tenantCanDeactivateAndReactivateSystemOptionWithoutChangingItsDefinition() {
        setTenant(tenantA);
        industryTemplateService.seedTenantDefaults(tenantA, "FABRICATION_STEEL");
        ConfigOptionDto protectedOption = configService.getOptions("PRODUCTION_STAGES").get(0);

        assertThat(protectedOption.isSystem()).isTrue();
        configService.setOptionActive("PRODUCTION_STAGES", protectedOption.id(), false);
        ConfigOptionDto deactivated = configService.getOptions("PRODUCTION_STAGES", true).stream()
                .filter(option -> option.id().equals(protectedOption.id())).findFirst().orElseThrow();
        assertThat(deactivated.isActive()).isFalse();
        assertThat(deactivated.isSystem()).isTrue();
        assertThat(deactivated.label()).isEqualTo(protectedOption.label());

        configService.setOptionActive("PRODUCTION_STAGES", protectedOption.id(), true);
        assertThat(configService.getOptions("PRODUCTION_STAGES").stream()
                .filter(option -> option.id().equals(protectedOption.id())).findFirst().orElseThrow().isActive()).isTrue();
    }

    @Test
    void referencedItemCategoryCanBeDeactivatedButCannotBeRenamed() {
        setTenant(tenantB);
        ConfigOptionDto category = configService.createOption("ITEM_CATEGORY", "USED_CATEGORY", "Used Category", 10);
        UUID uomId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO units_of_measure (tenant_id, name, symbol, uom_type) "
                        + "VALUES (?, 'Each', 'EA_CONFIG_TEST', 'quantity') RETURNING id::text",
                String.class, tenantB));
        jdbc.update("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id, category_id)
                VALUES (?, 'CONFIG_TEST_ITEM', 'Config test item', 'raw_material', ?, ?)
                """, tenantB, uomId, category.id());

        assertThatThrownBy(() -> configService.updateOption("ITEM_CATEGORY", category.id(), "Renamed"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("referenced by business records");
        configService.setOptionActive("ITEM_CATEGORY", category.id(), false);
        ConfigOptionDto deactivated = configService.getOptions("ITEM_CATEGORY", true).stream()
                .filter(option -> option.id().equals(category.id())).findFirst().orElseThrow();
        assertThat(deactivated.isActive()).isFalse();
        assertThat(deactivated.label()).isEqualTo("Used Category");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM items WHERE tenant_id = ? AND category_id = ?",
                Integer.class, tenantB, category.id())).isEqualTo(1);
    }
}
