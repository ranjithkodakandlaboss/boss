package in.boss.masterdata.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "hsn_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HsnCodeEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true)
    private String code;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "gst_rate")
    private BigDecimal gstRate;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
