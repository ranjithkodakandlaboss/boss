package in.boss.shared.config;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.AfterEach;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CI-gated test verifying that all six approved industry template seed sets
 * are correctly applied at tenant provisioning (issues #88, #91 and DoD).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IndustryTemplateSeedingTest {

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
    @Autowired IndustryTemplateService industryTemplateService;
    @Autowired ConfigService configService;

    @AfterEach
    void clear() {
        BossContextHolder.clear();
    }

    private UUID createTenant(String slug) {
        UUID groupId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO customer_groups (name, slug) VALUES ('Group " + slug + "','" + slug + "-grp') "
                + "ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name RETURNING id::text",
                String.class));

        return UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_registry (customer_group_id, name, slug, status) "
                + "VALUES (?, 'Tenant " + slug + "', '" + slug + "', 'active') "
                + "ON CONFLICT (slug) DO UPDATE SET status = 'active' RETURNING id::text",
                String.class, groupId));
    }

    private void setContext(UUID tenantId) {
        BossContextHolder.set(new BossContext(
                tenantId, UUID.randomUUID(), Set.of("OWNER"), null, "uid-" + tenantId));
    }

    @Test
    void allSixApprovedIndustryTypes_seedSuccessfully() {
        String[] approvedIndustries = {
            "FABRICATION_STEEL",
            "PRECISION_MACHINING",
            "ELECTRONICS_ASSEMBLY",
            "FORGING",
            "CASTING",
            "GENERAL_MANUFACTURING"
        };

        for (String industry : approvedIndustries) {
            UUID tenantId = createTenant("seed-" + industry.toLowerCase().replace("_", "-"));

            int seeded = industryTemplateService.seedTenantDefaults(tenantId, industry);
            assertThat(seeded).as("Seeded count for " + industry).isGreaterThan(0);

            setContext(tenantId);

            // Item categories are in item_categories table
            List<ConfigOptionDto> itemCats = configService.getOptions("ITEM_CATEGORY");
            assertThat(itemCats).as("Item categories for " + industry).isNotEmpty();
            assertThat(itemCats).allMatch(ConfigOptionDto::isSystem);

            // Production stages are in tenant_config_options
            List<ConfigOptionDto> stages = configService.getOptions("PRODUCTION_STAGES");
            assertThat(stages).as("Production stages for " + industry).isNotEmpty();
            assertThat(stages).allMatch(ConfigOptionDto::isSystem);

            // Inspection types
            List<ConfigOptionDto> inspTypes = configService.getOptions("INSPECTION_TYPES");
            assertThat(inspTypes).as("Inspection types for " + industry).isNotEmpty();

            // Rejection reasons
            List<ConfigOptionDto> rejReasons = configService.getOptions("REJECTION_REASONS");
            assertThat(rejReasons).as("Rejection reasons for " + industry).isNotEmpty();

            // Quality screen categories. Inspection type and severity codes are
            // the values stored on inspections and NCRs.
            assertThat(configService.getOptions("INSPECTION_TYPE"))
                    .extracting(ConfigOptionDto::code)
                    .containsExactly("incoming", "in_process", "final", "customer", "dispatch_readiness");
            assertThat(configService.getOptions("DEFECT_SEVERITY"))
                    .extracting(ConfigOptionDto::code)
                    .containsExactly("critical", "major", "medium", "minor");
            assertThat(configService.getOptions("DEFECT_CATEGORY"))
                    .extracting(ConfigOptionDto::code)
                    .containsExactlyInAnyOrderElementsOf(
                            rejReasons.stream().map(ConfigOptionDto::code).toList());

            // Standard UOMs seeded in units_of_measure
            Integer uomCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM units_of_measure WHERE tenant_id = ?",
                    Integer.class,
                    tenantId);
            assertThat(uomCount).isGreaterThanOrEqualTo(16);

            // Onboarding config recorded
            Integer onboardingCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tenant_onboarding_config "
                    + "WHERE tenant_id = ? AND industry_type = ? AND onboarding_completed_at IS NOT NULL",
                    Integer.class,
                    tenantId,
                    industry);
            assertThat(onboardingCount).isEqualTo(1);
        }
    }

    @Test
    void fabricationSteel_createsExpectedStagesAndCategories() {
        UUID tenantId = createTenant("steel-pilot");
        industryTemplateService.seedTenantDefaults(tenantId, "FABRICATION_STEEL");

        setContext(tenantId);
        List<ConfigOptionDto> stages = configService.getOptions("PRODUCTION_STAGES");
        List<String> stageCodes = stages.stream().map(ConfigOptionDto::code).toList();
        assertThat(stageCodes).contains("CUTTING", "BENDING", "WELDING", "GRINDING");

        List<ConfigOptionDto> categories = configService.getOptions("ITEM_CATEGORY");
        List<String> catCodes = categories.stream().map(ConfigOptionDto::code).toList();
        assertThat(catCodes).contains("HR_PLATE", "MS_STRUCTURAL", "SS_SHEET");
    }

    @Test
    void unapprovedIndustryType_throwsException() {
        UUID tenantId = createTenant("invalid-ind");
        assertThatThrownBy(() -> industryTemplateService.seedTenantDefaults(tenantId, "AEROSPACE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AEROSPACE");
    }

    @Test
    void seedingIsIdempotent_noDuplicatesOnSecondCall() {
        UUID tenantId = createTenant("idempotent-seed");
        int firstCount = industryTemplateService.seedTenantDefaults(tenantId, "PRECISION_MACHINING");
        assertThat(firstCount).isGreaterThan(0);

        int secondCount = industryTemplateService.seedTenantDefaults(tenantId, "PRECISION_MACHINING");
        assertThat(secondCount).isEqualTo(0);
    }
}
