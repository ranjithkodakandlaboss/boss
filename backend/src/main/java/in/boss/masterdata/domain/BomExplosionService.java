package in.boss.masterdata.domain;

import in.boss.masterdata.api.dto.BOMExplosionResultDto;

import java.math.BigDecimal;
import java.util.UUID;

public interface BomExplosionService {
    BOMExplosionResultDto explodeBOM(UUID itemId, BigDecimal quantity);
}
