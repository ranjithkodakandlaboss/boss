package in.boss.admin.api;

import java.util.List;

public record TenantPage(List<TenantSummary> content, int page, int totalElements) {
    public TenantPage {
        content = List.copyOf(content);
    }
}
