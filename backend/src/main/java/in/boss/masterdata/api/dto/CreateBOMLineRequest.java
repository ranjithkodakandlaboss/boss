package in.boss.masterdata.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class CreateBOMLineRequest {
    @NotNull(message = "componentItemId is required")
    private UUID componentItemId;

    @DecimalMin(value = "0.001", message = "qty must be at least 0.001")
    private BigDecimal qty;

    @NotNull(message = "uomId is required")
    private UUID uomId;

    @Min(value = 0, message = "scrapFactor must be >= 0")
    @Max(value = 100, message = "scrapFactor must be <= 100")
    private BigDecimal scrapFactor;

    private Integer level;

    @Size(max = 256, message = "quantityFormula must be at most 256 characters")
    private String quantityFormula;
}
