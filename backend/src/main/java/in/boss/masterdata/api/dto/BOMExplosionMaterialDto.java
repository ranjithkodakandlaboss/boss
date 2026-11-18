package in.boss.masterdata.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BOMExplosionMaterialDto {
    private UUID itemId;
    private String itemCode;
    private String itemName;
    private BigDecimal qty;
    private UUID uomId;
    private String uomSymbol;
    private Integer level;
}
