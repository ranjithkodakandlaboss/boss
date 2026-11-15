package in.boss.masterdata.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateSupplierRequest {
    @NotBlank(message = "code is required")
    private String code;

    @NotBlank(message = "name is required")
    private String name;

    private String legalName;
    private String gstin;
    private String pan;
    private UUID supplierCategoryId;
    private String supplierCategoryLabel;
    private Map<String, Object> address;
    private Integer paymentTerms;
    private BigDecimal creditLimit;
    private List<ContactDto> contacts;
}
