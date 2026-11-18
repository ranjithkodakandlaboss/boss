package in.boss.masterdata.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class CreateItemRequest {

    @NotBlank(message = "code is required")
    @Size(min = 1, max = 50, message = "code must be between 1 and 50 characters")
    private String code;

    @NotBlank(message = "name is required")
    @Size(min = 1, max = 200, message = "name must be between 1 and 200 characters")
    private String name;

    private String description;

    @NotBlank(message = "itemType is required")
    private String itemType;

    private UUID categoryId;
    private String categoryLabel;

    @NotNull(message = "uomId is required")
    private UUID uomId;

    private String hsnCode;
    private BigDecimal standardCost;
    private BigDecimal reorderPoint;
    private String productKind;
    private List<ItemSpecification> specifications;
}
