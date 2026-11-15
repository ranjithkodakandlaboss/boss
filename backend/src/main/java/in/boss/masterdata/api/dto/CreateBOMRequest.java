package in.boss.masterdata.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateBOMRequest {
    @NotEmpty(message = "lines cannot be empty")
    @Valid
    private List<CreateBOMLineRequest> lines;
}
