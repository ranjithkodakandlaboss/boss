package in.boss.masterdata.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateItemCategoryRequest {
    @NotBlank(message = "code is required")
    private String code;

    @NotBlank(message = "name is required")
    private String name;

    private UUID parentId;
    private String description;
}
