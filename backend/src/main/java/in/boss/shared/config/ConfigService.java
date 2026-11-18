package in.boss.shared.config;

import in.boss.shared.context.BossContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ConfigService {

    private final ConfigOptionQueryService queryService;
    private final ConfigOptionCreateService createService;
    private final ConfigOptionLifecycleService lifecycleService;
    private final ConfigOptionOrderingService orderingService;

    public ConfigService(ConfigOptionQueryService queryService, ConfigOptionCreateService createService,
                         ConfigOptionLifecycleService lifecycleService,
                         ConfigOptionOrderingService orderingService) {
        this.queryService = queryService;
        this.createService = createService;
        this.lifecycleService = lifecycleService;
        this.orderingService = orderingService;
    }

    public List<ConfigCategoryDto> getCategories() {
        return queryService.getCategories();
    }

    public List<ConfigOptionDto> getOptions(String categoryCode, boolean includeInactive) {
        var context = BossContextHolder.get();
        if (context == null) throw new IllegalStateException("BossContext required");
        return queryService.getOptions(context.tenantId(), categoryCode, includeInactive);
    }

    @PreAuthorize("hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER')")
    @Transactional
    public ConfigOptionDto createOption(String categoryCode, String code, String label, int sortOrder) {
        return createService.createOption(categoryCode, code, label, sortOrder);
    }

    @PreAuthorize("hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER')")
    @Transactional
    public ConfigOptionDto updateOption(String categoryCode, UUID optionId, String label) {
        return lifecycleService.updateOption(categoryCode, optionId, label);
    }

    @PreAuthorize("hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER')")
    @Transactional
    public void setOptionActive(String categoryCode, UUID optionId, boolean active) {
        lifecycleService.setOptionActive(categoryCode, optionId, active);
    }

    @PreAuthorize("hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER')")
    @Transactional
    public void reorderOptions(String categoryCode, List<UUID> optionIds) {
        orderingService.reorderOptions(categoryCode, optionIds);
    }

    public List<ConfigOptionDto> getOptions(String categoryCode) {
        return getOptions(categoryCode, false);
    }
}
