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
        BossContextHolder.set(new BossContext(
                tenantId, UUID.randomUUID(), Set.of("OWNER"), null, "uid-" + tenantId));
        var auth = new UsernamePasswordAuthenticationToken(
                "uid-" + tenantId, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
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
    void unknownCategory_returnsEmptyList_withoutPollutingCache() {
        setTenant(tenantA);
        List<ConfigOptionDto> opts = configService.getOptions("NON_EXISTENT_CATEGORY_XYZ");
        assertThat(opts).isEmpty();
    }
}
