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
public class ContactDto {
    private UUID id;
    private String name;
    private String role;
    private String email;
    private String phone;
    private Boolean isPrimary;
}
