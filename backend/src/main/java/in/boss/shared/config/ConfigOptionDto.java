package in.boss.shared.config;

import java.util.UUID;

public record ConfigOptionDto(
        UUID id,
        String categoryCode,
        String code,
        String label,
        int sortOrder,
        boolean isSystem,
        boolean isActive
) {}
