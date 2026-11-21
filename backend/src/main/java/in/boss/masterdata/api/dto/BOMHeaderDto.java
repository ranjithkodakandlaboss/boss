package in.boss.masterdata.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BOMHeaderDto {
    private UUID id;
    private UUID itemId;
    private String itemCode;
    private String itemName;
    private Integer version;
    private String status;
    private BigDecimal labourCostPerUnit;
    private BigDecimal machineCostPerUnit;
    private BigDecimal otherCostPerUnit;
    private List<BOMLineDto> lines;
}
