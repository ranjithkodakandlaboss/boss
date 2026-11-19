package in.boss.masterdata.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerDto {
    private UUID id;
    private String code;
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
    private String status;
    private List<ContactDto> contacts;
    private Instant createdAt;
}
