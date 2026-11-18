package in.boss.masterdata.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BOMExplosionResultDto {
    private UUID itemId;
    private UUID bomHeaderId;
    private String bomVersion;
    private BigDecimal quantity;
    private Map<String, BigDecimal> specifications;
    private List<BOMRevisionSnapshotDto> bomRevisions;
    private List<BOMExplosionMaterialDto> flatMaterials;
}
