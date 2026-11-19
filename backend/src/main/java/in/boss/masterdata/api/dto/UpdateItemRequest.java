package in.boss.masterdata.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
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
public class UpdateItemRequest {
    private String name;
    private String description;
    private UUID categoryId;
    private String categoryLabel;
    private String hsnCode;
    private BigDecimal standardCost;
    private BigDecimal reorderPoint;
    private String status;
    private String productKind;
    private List<ItemSpecification> specifications;
    @JsonIgnore
    private boolean productConfigurationProvided;

    @JsonSetter("productKind")
    public void setProductKind(String productKind) {
        this.productKind = productKind;
        this.productConfigurationProvided = true;
    }

    @JsonSetter("specifications")
    public void setSpecifications(List<ItemSpecification> specifications) {
        this.specifications = specifications;
        this.productConfigurationProvided = true;
    }

    /** Explicit JSON null sets the flag; the builder only sets the fields. Either one is a change. */
    @JsonIgnore
    public boolean changesProductConfiguration() {
        return productConfigurationProvided || productKind != null || specifications != null;
    }
}
