package in.boss.admin.api;

import java.util.List;

public record PagedTenants(List<TenantSummary> content, int page, long totalElements) { }
