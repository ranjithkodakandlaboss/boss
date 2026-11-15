package in.boss.masterdata.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateUomRequest {
    @NotBlank(message = "name is required")
    private String name;

    @NotBlank(message = "symbol is required")
    private String symbol;

    private String uomType;
    private Boolean isBase;
}
