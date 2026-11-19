package in.boss.masterdata.events;

import java.util.UUID;

public record CustomerDeactivationRequested(UUID tenantId, UUID customerId) {}
