package in.boss.masterdata.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateBOMRequest {
    private BigDecimal labourCostPerUnit;
    private BigDecimal machineCostPerUnit;
    private BigDecimal otherCostPerUnit;

    @NotEmpty(message = "lines cannot be empty")
    @Valid
    private List<CreateBOMLineRequest> lines;
}
