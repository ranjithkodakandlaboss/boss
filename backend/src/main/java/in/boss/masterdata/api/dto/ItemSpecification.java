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
public class ItemSpecification {
    private String key;
    private String label;
    private UUID uomId;
    private String uomSymbol;
    private BigDecimal minValue;
    private BigDecimal maxValue;
    private BigDecimal defaultValue;
}
