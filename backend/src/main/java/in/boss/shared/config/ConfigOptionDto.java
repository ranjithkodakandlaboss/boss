package in.boss.shared.config;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

public record ConfigOptionDto(
        UUID id,
        String categoryCode,
        String code,
        String label,
        int sortOrder,
        boolean isSystem,
        boolean isActive,
        @JsonInclude(JsonInclude.Include.NON_NULL) String description
) {
    public ConfigOptionDto(UUID id, String categoryCode, String code, String label, int sortOrder,
                           boolean isSystem, boolean isActive) {
        this(id, categoryCode, code, label, sortOrder, isSystem, isActive, null);
    }
}
