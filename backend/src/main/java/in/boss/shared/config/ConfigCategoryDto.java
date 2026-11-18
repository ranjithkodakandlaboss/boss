package in.boss.shared.config;

public record ConfigCategoryDto(
        String code,
        String name,
        String scope,
        boolean tenantEditable,
        boolean supportsOrdering
) {}
