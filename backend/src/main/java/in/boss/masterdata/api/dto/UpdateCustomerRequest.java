package in.boss.masterdata.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
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
public class UpdateCustomerRequest {
    private String name;
    private String legalName;
    private String gstin;
    private String pan;
    private UUID customerTypeId;
    private String customerTypeLabel;
    private Map<String, Object> address;
    private BigDecimal creditLimit;
    private Integer creditDays;
    private String status;
    private List<ContactDto> contacts;
    private Integer rating;
    @JsonIgnore
    private boolean ratingProvided;

    @JsonSetter("rating")
    public void setRating(Integer rating) {
        this.rating = rating;
        this.ratingProvided = true;
    }

    @JsonIgnore
    public boolean changesRating() {
        return ratingProvided || rating != null;
    }

    @JsonIgnore
    public boolean changesProfile() {
        return name != null || legalName != null || gstin != null || pan != null
                || customerTypeId != null || customerTypeLabel != null || address != null
                || creditLimit != null || creditDays != null || contacts != null || changesRating();
    }
}
