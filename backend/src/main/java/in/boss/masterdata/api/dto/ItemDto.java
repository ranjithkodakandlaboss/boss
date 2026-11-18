package in.boss.masterdata.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ItemDto {
    private UUID id;
    private String code;
    private String name;
    private String description;
    private String itemType;
    private UUID categoryId;
    private String categoryLabel;
    private UUID uomId;
    private String uomSymbol;
    private String hsnCode;
    private BigDecimal gstRate;
    private BigDecimal standardCost;
    private BigDecimal reorderPoint;
    private String status;
    private String productKind;
    private List<ItemSpecification> specifications;
    private Instant createdAt;
}
