package in.boss.masterdata.api.dto;

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
public class SupplierDto {
    private UUID id;
    private String code;
    private String name;
    private String legalName;
    private String gstin;
    private String pan;
    private UUID supplierCategoryId;
    private String supplierCategoryLabel;
    private Map<String, Object> address;
    private String status;
    private BigDecimal performanceScore;
    private Integer paymentTerms;
    private BigDecimal creditLimit;
    private List<ContactDto> contacts;
}
