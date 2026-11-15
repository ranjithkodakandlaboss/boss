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
public class UpdateItemRequest {
    private String name;
    private String description;
    private UUID categoryId;
    private String categoryLabel;
    private String hsnCode;
    private BigDecimal standardCost;
    private BigDecimal reorderPoint;
    private String status;
}
