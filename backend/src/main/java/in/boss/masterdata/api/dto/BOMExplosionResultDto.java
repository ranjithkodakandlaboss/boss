package in.boss.masterdata.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BOMExplosionResultDto {
    private UUID itemId;
    private List<BOMExplosionMaterialDto> flatMaterials;
}
