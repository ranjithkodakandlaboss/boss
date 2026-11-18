package in.boss.masterdata.domain;

import in.boss.masterdata.api.dto.BOMExplosionResultDto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.UUID;

public interface BomExplosionService {
    BOMExplosionResultDto explodeBOM(UUID itemId, BigDecimal quantity);
    BOMExplosionResultDto explodeBOM(UUID itemId, BigDecimal quantity, Map<String, BigDecimal> specifications);
    BOMExplosionResultDto explodeBOMExact(UUID itemId, BigDecimal quantity);
    List<String> requiredSpecificationNames(UUID itemId);
}
