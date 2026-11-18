package in.boss.masterdata.api.dto;

import java.util.UUID;

public record BOMRevisionSnapshotDto(UUID itemId, UUID bomHeaderId, String version) {}
