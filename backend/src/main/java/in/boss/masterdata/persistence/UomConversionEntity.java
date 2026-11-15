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
@Table(name = "uom_conversions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UomConversionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "from_uom_id", nullable = false)
    private UUID fromUomId;

    @Column(name = "to_uom_id", nullable = false)
    private UUID toUomId;

    @Column(name = "factor", nullable = false)
    private BigDecimal factor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
