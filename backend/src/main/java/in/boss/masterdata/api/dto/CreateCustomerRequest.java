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
public class CreateCustomerRequest {
    @NotBlank(message = "code is required")
    private String code;

    @NotBlank(message = "name is required")
    private String name;

    private String legalName;
    private String gstin;
    private String pan;
    private UUID customerTypeId;
    private String customerTypeLabel;
    private Map<String, Object> address;
    private BigDecimal creditLimit;
    private Integer creditDays;
    private Integer rating;
    private List<ContactDto> contacts;
}
