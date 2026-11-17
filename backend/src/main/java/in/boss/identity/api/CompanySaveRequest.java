package in.boss.identity.api;

import java.util.List;
import java.util.UUID;

public record CompanySaveRequest(
        String companyName,
        String tradingName,
        String legalName,
        String entityType,
        String pan,
        String addressLine,
        String city,
        String state,
        String email,
        String phone,
        String gstin,
        Boolean gstNotApplicable,
        List<SiteInput> sites,
        List<StoreInput> stores) {
    public record SiteInput(UUID id, String name, String code, String siteType, String addressLine, String city, String state) { }
    public record StoreInput(String siteCode, String name, String code, String storeType) { }
}
