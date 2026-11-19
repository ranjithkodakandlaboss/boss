package in.boss.masterdata.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

public record BOMExplosionRequest(
        @NotNull @DecimalMin(value = "0.001") BigDecimal quantity,
        @Size(max = 30) Map<String, @NotNull @DecimalMin("0.00") BigDecimal> specifications) {}
