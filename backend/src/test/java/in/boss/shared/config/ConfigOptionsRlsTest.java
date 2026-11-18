package in.boss.shared.config;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import in.boss.masterdata.domain.ItemCategoryService;
import in.boss.masterdata.domain.LifecycleException;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    @Autowired ItemCategoryService itemCategoryService;
    @Autowired WebApplicationContext webContext;

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

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_config_option_history
                WHERE tenant_id = ? AND option_id = ? AND change_type = 'update'
                  AND old_label = 'Card' AND new_label = 'Card payment'
                """, Integer.class, tenantA, first.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_config_option_history
                WHERE tenant_id = ? AND option_id = ? AND change_type IN ('deactivate', 'reactivate')
                """, Integer.class, tenantA, second.id())).isEqualTo(1);

        List<ConfigOptionDto> options = configService.getOptions("PAYMENT_MODES", true);
        assertThat(options).filteredOn(option -> option.id().equals(first.id())).singleElement()
                .extracting(ConfigOptionDto::label).isEqualTo("Card payment");
        assertThat(options).filteredOn(option -> option.id().equals(second.id())).singleElement()
                .extracting(ConfigOptionDto::isActive).isEqualTo(false);
        assertThat(options.stream().filter(option -> !option.isSystem()).map(ConfigOptionDto::id).toList())
                .containsExactlyElementsOf(reordered);

        configService.setOptionActive("PAYMENT_MODES", second.id(), true);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_config_option_history
                WHERE tenant_id = ? AND option_id = ? AND change_type = 'reactivate'
                """, Integer.class, tenantA, second.id())).isEqualTo(1);
    }

    @Test
    void optionReadsDoNotKeepStaleProcessLocalValues() {
        setTenant(tenantA);
        ConfigOptionDto option = configService.createOption("PAYMENT_MODES", "LIVE_OPTION", "Live option", 10);
        assertThat(configService.getOptions("PAYMENT_MODES"))
                .anyMatch(value -> value.id().equals(option.id()));

        jdbc.update("UPDATE tenant_config_options SET is_active = FALSE WHERE tenant_id = ? AND id = ?",
                tenantA, option.id());

        assertThat(configService.getOptions("PAYMENT_MODES"))
                .noneMatch(value -> value.id().equals(option.id()));
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
    void tenantCannotDeactivateSystemOptions() {
        setTenant(tenantA);
        industryTemplateService.seedTenantDefaults(tenantA, "FABRICATION_STEEL");
        ConfigOptionDto protectedOption = configService.getOptions("PRODUCTION_STAGES").get(0);

        assertThat(protectedOption.isSystem()).isTrue();
        assertThatThrownBy(() -> configService.setOptionActive("PRODUCTION_STAGES", protectedOption.id(), false))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("System-controlled");
        assertThat(configService.getOptions("PRODUCTION_STAGES"))
                .anyMatch(option -> option.id().equals(protectedOption.id()));

        ConfigOptionDto category = configService.getOptions("ITEM_CATEGORY").stream()
                .filter(ConfigOptionDto::isSystem).findFirst().orElseThrow();
        assertThatThrownBy(() -> configService.setOptionActive("ITEM_CATEGORY", category.id(), false))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("System-controlled");
        assertThatThrownBy(() -> itemCategoryService.deleteCategory(category.id()))
                .isInstanceOf(LifecycleException.class)
                .hasMessageContaining("System-controlled");
    }

    @Test
    void activeItemBlocksDeactivationButInactiveHistoryIsPreserved() {
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
                .hasMessageContaining("Item-category labels cannot be changed");
        assertThatThrownBy(() -> configService.setOptionActive("ITEM_CATEGORY", category.id(), false))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("1 active item");
        jdbc.update("UPDATE items SET is_active = FALSE WHERE tenant_id = ? AND category_id = ?",
                tenantB, category.id());
        configService.setOptionActive("ITEM_CATEGORY", category.id(), false);
        ConfigOptionDto deactivated = configService.getOptions("ITEM_CATEGORY", true).stream()
                .filter(option -> option.id().equals(category.id())).findFirst().orElseThrow();
        assertThat(deactivated.isActive()).isFalse();
        assertThat(deactivated.label()).isEqualTo("Used Category");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM items WHERE tenant_id = ? AND category_id = ?",
                Integer.class, tenantB, category.id())).isEqualTo(1);
    }

    @Test
    void activeChildrenBlockDeactivationAndCannotReactivateUnderInactiveParent() {
        setTenant(tenantA);
        ConfigOptionDto parent = configService.createOption("ITEM_CATEGORY", "PARENT_GUARD", "Parent guard", 10);
        ConfigOptionDto child = configService.createOption("ITEM_CATEGORY", "CHILD_GUARD", "Child guard", 10);
        jdbc.update("UPDATE item_categories SET parent_id = ? WHERE id = ? AND tenant_id = ?",
                parent.id(), child.id(), tenantA);
        assertThatThrownBy(() -> configService.setOptionActive("ITEM_CATEGORY", parent.id(), false))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("1 active child category");
        configService.setOptionActive("ITEM_CATEGORY", child.id(), false);
        configService.setOptionActive("ITEM_CATEGORY", parent.id(), false);
        assertThatThrownBy(() -> configService.setOptionActive("ITEM_CATEGORY", child.id(), true))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("inactive or unavailable parent");
        setTenant(tenantB);
        assertThatThrownBy(() -> configService.setOptionActive("ITEM_CATEGORY", parent.id(), true))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Configuration option not found");
    }

    @Test
    void itemCategoryOptionsEndpointIncludesProtectedAndInactiveMetadata() throws Exception {
        setTenant(tenantA);
        ConfigOptionDto category = configService.createOption("ITEM_CATEGORY", "ENDPOINT_GUARD", "Endpoint guard", 10);
        jdbc.update("UPDATE item_categories SET description = 'Example category' WHERE id = ?", category.id());
        configService.setOptionActive("ITEM_CATEGORY", category.id(), false);
        MockMvcBuilders.webAppContextSetup(webContext).build()
                .perform(get("/api/v1/config/options/ITEM_CATEGORY").param("includeInactive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'ENDPOINT_GUARD')].isActive").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$[?(@.code == 'ENDPOINT_GUARD')].isSystem").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$[?(@.code == 'ENDPOINT_GUARD')].description")
                        .value(org.hamcrest.Matchers.contains("Example category")));
    }
}
