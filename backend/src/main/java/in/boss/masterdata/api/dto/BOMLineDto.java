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
public class BOMLineDto {
    private UUID id;
    private Integer seq;
    private UUID componentItemId;
    private String componentItemCode;
    private String componentItemName;
    private String componentItemType;
    private BigDecimal qty;
    private UUID uomId;
    private String uomSymbol;
    private BigDecimal scrapFactor;
    private Integer level;
    private String quantityFormula;
    private BigDecimal unitCost;
    private BigDecimal totalCost;
}
