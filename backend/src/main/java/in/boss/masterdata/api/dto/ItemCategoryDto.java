package in.boss.masterdata.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ItemCategoryDto {
    private UUID id;
    private String code;
    private String name;
    private UUID parentId;
    private String description;
    private Boolean isActive;
}
